import Foundation
import HealthKit

/// Reads recent health data from HealthKit.
///
/// This is the only reason the watch app has to exist. Heart rate, sleep,
/// workouts and the rest live behind HealthKit, which is reachable only from
/// code running on the watch. No amount of work on the Android side reaches
/// them.
@MainActor
final class HealthReader: ObservableObject {

    @Published private(set) var authorised = false
    @Published private(set) var lastError: String?

    private let store = HKHealthStore()

    /** Maximum samples fetched per type per sync. */
    static let queryLimit = 500

    /// Where the last successful read stopped, so each sync only sends what is
    /// new rather than re-uploading the whole history.
    private var watermark: Date {
        get {
            let stored = UserDefaults.standard.double(forKey: "wristbridge.watermark")
            // First run: a day of history is enough to show something useful
            // without flooding the link.
            return stored > 0
                ? Date(timeIntervalSince1970: stored)
                : Date().addingTimeInterval(-86_400)
        }
        set { UserDefaults.standard.set(newValue.timeIntervalSince1970, forKey: "wristbridge.watermark") }
    }

    /// Quantity types read on every sync, paired with the unit and the kind
    /// name the Android side expects.
    private var quantityTypes: [(HKQuantityType, HKUnit, String)] {
        var types: [(HKQuantityType, HKUnit, String)] = []
        func add(_ identifier: HKQuantityTypeIdentifier, _ unit: HKUnit, _ kind: String) {
            if let type = HKQuantityType.quantityType(forIdentifier: identifier) {
                types.append((type, unit, kind))
            }
        }
        add(.heartRate, HKUnit.count().unitDivided(by: .minute()), "heartRate")
        add(.restingHeartRate, HKUnit.count().unitDivided(by: .minute()), "restingHeartRate")
        add(.stepCount, .count(), "steps")
        add(.activeEnergyBurned, .kilocalorie(), "activeEnergy")
        // Read as a fraction and scaled below; see collectNewSamples.
        add(.oxygenSaturation, .percent(), "oxygenSaturation")
        add(.appleExerciseTime, .minute(), "exerciseMinutes")
        add(.appleStandTime, .minute(), "standHours")
        return types
    }

    private var readTypes: Set<HKObjectType> {
        var types = Set<HKObjectType>(quantityTypes.map { $0.0 })
        if let sleep = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) {
            types.insert(sleep)
        }
        types.insert(HKObjectType.workoutType())
        return types
    }

    func requestAuthorisation() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            lastError = "Health data is not available on this device"
            return
        }
        do {
            // Read-only: the bridge never writes back into HealthKit.
            try await store.requestAuthorization(toShare: [], read: readTypes)
            authorised = true
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Collects everything recorded since the last successful sync.
    func collectNewSamples() async -> [HealthSample] {
        guard HKHealthStore.isHealthDataAvailable() else { return [] }

        let since = watermark
        let until = Date()
        var collected: [HealthSample] = []

        for (type, unit, kind) in quantityTypes {
            let samples = await query(type: type, from: since, to: until)
            collected += samples.compactMap { sample in
                guard let quantity = sample as? HKQuantitySample else { return nil }
                let raw = quantity.quantity.doubleValue(for: unit)
                // HKUnit.percent() is a fraction, so a 98% reading comes back
                // as 0.98. Sent unscaled the phone would display "1 %".
                let value = kind == "oxygenSaturation" ? raw * 100 : raw
                let label = kind == "oxygenSaturation" ? "%" : unit.unitString
                return HealthSample(
                    kind: kind,
                    value: value,
                    unit: label,
                    start: quantity.startDate,
                    end: quantity.endDate
                )
            }
        }

        collected += await collectSleep(from: since, to: until)
        collected += await collectWorkouts(from: since, to: until)

        // Deliberately does NOT advance the watermark. If the link drops
        // mid-send these samples would be skipped for ever; the caller calls
        // markDelivered only once they are actually on the phone.
        return collected.sorted { $0.start < $1.start }
    }

    /**
     Advances the sync point past samples the phone has accepted.

     Takes the newest delivered sample rather than "now", because each query is
     capped at `queryLimit` per type: on a busy day there can be more history
     left behind, and moving to the wall clock would silently skip it.
     */
    func markDelivered(_ samples: [HealthSample]) {
        // Deliberately the latest *start*, matching the predicate below, which
        // selects on startDate. Using the latest end would carry the marker
        // past the whole span of a long sample such as a workout or a night's
        // sleep, and everything recorded inside that span would never be read.
        // A sample landing exactly on the boundary may be re-sent; the phone
        // discards repeats by (kind, start).
        guard let newest = samples.map({ $0.start }).max() else { return }
        if newest > watermark {
            watermark = newest
        }
    }

    private func collectSleep(from: Date, to: Date) async -> [HealthSample] {
        guard let type = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) else { return [] }
        let samples = await query(type: type, from: from, to: to)
        return samples.compactMap { sample in
            guard let category = sample as? HKCategorySample else { return nil }
            // Only asleep states are interesting; "in bed" double-counts.
            let asleep: Set<Int> = [
                HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
                HKCategoryValueSleepAnalysis.asleepCore.rawValue,
                HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
                HKCategoryValueSleepAnalysis.asleepREM.rawValue,
            ]
            guard asleep.contains(category.value) else { return nil }
            let minutes = category.endDate.timeIntervalSince(category.startDate) / 60
            return HealthSample(
                kind: "sleep",
                value: minutes,
                unit: "min",
                start: category.startDate,
                end: category.endDate
            )
        }
    }

    private func collectWorkouts(from: Date, to: Date) async -> [HealthSample] {
        let samples = await query(type: HKObjectType.workoutType(), from: from, to: to)
        return samples.compactMap { sample in
            guard let workout = sample as? HKWorkout else { return nil }
            let minutes = workout.duration / 60
            return HealthSample(
                kind: "workout",
                value: minutes,
                unit: "min",
                start: workout.startDate,
                end: workout.endDate
            )
        }
    }

    private func query(type: HKSampleType, from: Date, to: Date) async -> [HKSample] {
        await withCheckedContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(
                withStart: from,
                end: to,
                options: .strictStartDate
            )
            let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
            let query = HKSampleQuery(
                sampleType: type,
                predicate: predicate,
                // A cap keeps one very active day from producing a payload the
                // BLE link would spend minutes draining. markDelivered accounts
                // for whatever this leaves behind.
                limit: Self.queryLimit,
                sortDescriptors: [sort]
            ) { _, samples, _ in
                continuation.resume(returning: samples ?? [])
            }
            store.execute(query)
        }
    }
}
