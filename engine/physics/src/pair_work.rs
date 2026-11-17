//! Balanced, deterministic partitions of the useful lexicographic pair stream.
//! Build once while source membership is fixed, then reuse for every force sweep.

#[derive(Debug, Default)]
pub(super) struct PairPartition {
    spans: Vec<PairSpan>,
}

#[derive(Debug)]
struct PairSpan {
    row: usize,
    start: usize,
    end: usize,
    source_only: bool,
}

impl PairPartition {
    #[inline]
    pub(super) fn for_each_pair(&self, sources: &[usize], mut visit: impl FnMut(usize, usize)) {
        for span in &self.spans {
            if span.source_only {
                for &partner in &sources[span.start..span.end] {
                    visit(span.row, partner);
                }
            } else {
                for partner in span.start..span.end {
                    visit(span.row, partner);
                }
            }
        }
    }

    #[cfg(test)]
    pub(super) fn pair_count(&self) -> usize {
        self.spans.iter().map(|span| span.end - span.start).sum()
    }
}

/// Sources must be the sorted, unique indices of the current nonzero masses.
/// Each returned partition is nonempty, and loads differ by at most one pair.
/// A long row can cross partition boundaries; no complete pair list is stored.
pub(super) fn balanced_pair_partitions(
    body_count: usize,
    sources: &[usize],
    worker_limit: usize,
) -> Vec<PairPartition> {
    debug_assert!(sources.windows(2).all(|pair| pair[0] < pair[1]));
    debug_assert!(sources.last().is_none_or(|&index| index < body_count));
    let total_pairs = super::physical_pair_count(body_count, sources.len());
    let workers = (worker_limit as u64).min(total_pairs) as usize;
    if workers == 0 {
        return Vec::new();
    }
    let base = total_pairs / workers as u64;
    let remainder = total_pairs % workers as u64;
    let mut partitions: Vec<_> = (0..workers).map(|_| PairPartition::default()).collect();
    let mut worker = 0;
    let mut remaining = base + u64::from(remainder > 0);
    let mut source_cursor = 0;
    for row in 0..body_count {
        let is_source = sources.get(source_cursor) == Some(&row);
        if is_source {
            source_cursor += 1;
        }
        let (mut start, end) = if is_source {
            (row + 1, body_count)
        } else {
            (source_cursor, sources.len())
        };
        while start < end {
            let count = remaining.min((end - start) as u64) as usize;
            partitions[worker].spans.push(PairSpan {
                row,
                start,
                end: start + count,
                source_only: !is_source,
            });
            start += count;
            remaining -= count as u64;
            if remaining == 0 {
                worker += 1;
                remaining = base + u64::from((worker as u64) < remainder);
            }
        }
    }
    debug_assert_eq!(worker, workers);
    partitions
}
