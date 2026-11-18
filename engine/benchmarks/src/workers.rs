//! A pool whose lifetime is exactly one measured JNI segment. Requests and
//! replies own their buffers; bounded channels allow only one sweep in flight.
//! Threads inherit the caller's current OS affinity and are joined before return.
use std::ops::Range;
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::kernels::target_acceleration;
use crate::model::{Body, V3};

struct Job {
    snapshot: Arc<Vec<Body>>,
    output: Vec<V3>,
}
struct Completed {
    worker: usize,
    output: Vec<V3>,
}
struct Worker {
    requests: SyncSender<Option<Job>>,
    handle: JoinHandle<()>,
}

pub struct SegmentPool {
    workers: Vec<Worker>,
    results: Receiver<Completed>,
    ranges: Vec<Range<usize>>,
    buffers: Vec<Vec<V3>>,
    snapshot: Arc<Vec<Body>>,
    pub evaluations: u64,
}

impl SegmentPool {
    pub fn new(bodies: &[Body], requested: usize) -> Result<Self, &'static str> {
        if bodies.is_empty() || !(1..=8).contains(&requested) {
            return Err("invalid segment worker shape");
        }
        let count = requested.min(bodies.len());
        let chunk = bodies.len().div_ceil(count);
        let ranges: Vec<_> = (0..bodies.len())
            .step_by(chunk)
            .map(|start| start..(start + chunk).min(bodies.len()))
            .collect();
        let sources: Arc<Vec<_>> = Arc::new(
            bodies
                .iter()
                .enumerate()
                .filter_map(|(i, b)| (b.mu > 0.0).then_some(i))
                .collect(),
        );
        let (results_tx, results) = mpsc::sync_channel(ranges.len());
        let buffers = ranges
            .iter()
            .map(|range| vec![[0.0; 3]; range.len()])
            .collect();
        let mut pool = Self {
            workers: Vec::new(),
            results,
            ranges,
            buffers,
            snapshot: Arc::new(bodies.to_vec()),
            evaluations: 0,
        };
        for (worker, range) in pool.ranges.iter().cloned().enumerate() {
            let (requests, receiver) = mpsc::sync_channel::<Option<Job>>(1);
            let completed = results_tx.clone();
            let source_indices = Arc::clone(&sources);
            let handle = thread::Builder::new()
                .name(format!("solarlab-bench-{worker}"))
                .spawn(move || {
                    while let Ok(Some(mut job)) = receiver.recv() {
                        for (offset, index) in range.clone().enumerate() {
                            job.output[offset] =
                                target_acceleration(&job.snapshot, &source_indices, index);
                        }
                        // Dropping the immutable snapshot before publishing the
                        // result lets the coordinator safely reuse its allocation.
                        drop(job.snapshot);
                        if completed
                            .send(Completed {
                                worker,
                                output: job.output,
                            })
                            .is_err()
                        {
                            break;
                        }
                    }
                })
                .map_err(|_| "could not start a segment worker")?;
            pool.workers.push(Worker { requests, handle });
        }
        drop(results_tx);
        Ok(pool)
    }

    pub fn worker_count(&self) -> usize {
        self.workers.len()
    }

    pub fn evaluate(&mut self, bodies: &[Body], out: &mut [V3]) -> Result<(), &'static str> {
        if bodies.len() != self.snapshot.len() || out.len() != bodies.len() {
            return Err("segment worker body shape changed");
        }
        let Some(snapshot) = Arc::get_mut(&mut self.snapshot) else {
            return Err("previous sweep still owns snapshot");
        };
        snapshot.copy_from_slice(bodies);
        for (index, worker) in self.workers.iter().enumerate() {
            worker
                .requests
                .send(Some(Job {
                    snapshot: Arc::clone(&self.snapshot),
                    output: std::mem::take(&mut self.buffers[index]),
                }))
                .map_err(|_| "segment worker request channel closed")?;
        }
        let mut seen = [false; 8];
        for _ in 0..self.workers.len() {
            let result = self
                .results
                .recv_timeout(Duration::from_secs(2))
                .map_err(|_| "segment worker response unavailable within bounded wait")?;
            if result.worker >= self.ranges.len()
                || seen[result.worker]
                || result.output.len() != self.ranges[result.worker].len()
            {
                return Err("segment worker returned an invalid partition");
            }
            seen[result.worker] = true;
            out[self.ranges[result.worker].clone()].copy_from_slice(&result.output);
            self.buffers[result.worker] = result.output;
        }
        self.evaluations += 1;
        Ok(())
    }

    pub fn finish(mut self) -> Result<usize, &'static str> {
        self.stop_and_join()
    }

    fn stop_and_join(&mut self) -> Result<usize, &'static str> {
        for worker in &self.workers {
            let _ = worker.requests.send(None);
        }
        let mut joined = 0;
        let mut failed = false;
        for worker in self.workers.drain(..) {
            if worker.handle.join().is_ok() {
                joined += 1;
            } else {
                failed = true;
            }
        }
        if failed {
            Err("a segment worker panicked")
        } else {
            Ok(joined)
        }
    }
}

impl Drop for SegmentPool {
    fn drop(&mut self) {
        let _ = self.stop_and_join();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::integrators::{leapfrog, persistent_leapfrog};
    use crate::kernels::{ForceEvaluator, ForceMode};
    use crate::model::fixture;

    #[test]
    fn persistent_pool_reuses_live_source_snapshots_without_changing_bits() {
        let mut f = fixture("canonical15").unwrap();
        let mut pool = SegmentPool::new(&f.bodies, 4).unwrap();
        for sweep in 0..8 {
            // Exercise source refresh and a 365-body partial target partition.
            f.bodies[1].p[0] += 0.01 * f64::from(sweep + 1);
            let mut expected = vec![[0.0; 3]; f.bodies.len()];
            let mut actual = expected.clone();
            ForceEvaluator::new(ForceMode::Direct, &f.bodies).evaluate(&f.bodies, &mut expected);
            pool.evaluate(&f.bodies, &mut actual).unwrap();
            assert_eq!(expected, actual);
        }
        assert_eq!(pool.evaluations, 8);
        assert_eq!(pool.finish().unwrap(), 4);
    }

    #[test]
    fn segment_pools_match_scalar_and_join_only_their_bounded_worker_set() {
        for fixture_id in ["sparse3", "canonical15", "dense192", "kepler"] {
            let f = fixture(fixture_id).unwrap();
            let mut expected = f.bodies.clone();
            leapfrog(&mut expected, ForceMode::Direct, 7, f.dt);
            for workers in [2, 4, 8] {
                let mut actual = f.bodies.clone();
                let stats = persistent_leapfrog(&mut actual, 7, f.dt, workers).unwrap();
                assert_eq!(expected, actual, "{fixture_id}, workers={workers}");
                assert_eq!(stats.force_evaluations, 8);
                assert_eq!(stats.source_snapshot_copies, Some(8));
                assert_eq!(stats.segment_workers_started, stats.segment_workers_joined);
                assert!(stats.segment_workers_started.unwrap() <= workers);
                assert!(stats.segment_workers_started.unwrap() <= f.bodies.len());
            }
        }
    }

    #[test]
    fn invalid_shapes_fail_and_pool_can_be_dropped_without_any_work() {
        assert!(SegmentPool::new(&[], 2).is_err());
        let f = fixture("kepler").unwrap();
        assert!(SegmentPool::new(&f.bodies, 0).is_err());
        assert!(SegmentPool::new(&f.bodies, 9).is_err());
        let mut pool = SegmentPool::new(&f.bodies, 8).unwrap();
        assert_eq!(pool.worker_count(), 2);
        assert!(pool.evaluate(&f.bodies, &mut [[0.0; 3]; 1]).is_err());
        drop(pool);
        drop(SegmentPool::new(&f.bodies, 2).unwrap());
    }
}
