use std::env;
use std::process::ExitCode;

use serde_json::json;
use solarlab_conformance::{
    report_exit_code, run_report, scenario_ids, ConformanceReport, ConformanceSummary,
    ScenarioReport, REPORT_SCHEMA_VERSION,
};

fn main() -> ExitCode {
    match run() {
        Ok(code) => ExitCode::from(code),
        Err(message) => {
            eprintln!("{message}");
            ExitCode::from(2)
        }
    }
}

fn run() -> Result<u8, String> {
    run_with_args(env::args().skip(1))
}

fn run_with_args<I, S>(args: I) -> Result<u8, String>
where
    I: IntoIterator<Item = S>,
    S: Into<String>,
{
    let mut args = args.into_iter().map(Into::into);
    let mut scenarios = Vec::new();
    let mut pretty = false;
    let mut list_scenarios = false;

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--scenario" => {
                let Some(scenario_id) = args.next() else {
                    return Err("--scenario requires an id".to_owned());
                };
                scenarios.push(scenario_id);
            }
            "--pretty" => pretty = true,
            "--list-scenarios" => list_scenarios = true,
            "--help" | "-h" => {
                print_help();
                return Ok(0);
            }
            other => {
                return Err(format!("unknown argument: {other}"));
            }
        }
    }

    if list_scenarios {
        for scenario_id in scenario_ids() {
            println!("{scenario_id}");
        }
        return Ok(0);
    }

    let commit_sha = env::var("SOLARLAB_COMMIT_SHA")
        .ok()
        .or_else(|| env::var("GITHUB_SHA").ok());
    let report = run_report(&scenarios, commit_sha)?;
    let rendered = if pretty {
        serde_json::to_string_pretty(&report)
    } else {
        serde_json::to_string(&report)
    }
    .map_err(|error| format!("failed to serialize conformance report: {error}"))?;

    println!("{rendered}");
    Ok(cli_exit_code_for_report(&report))
}

fn print_help() {
    println!("solarlab-conformance");
    println!();
    println!("Usage:");
    println!("  cargo run -p solarlab-conformance -- [--pretty] [--scenario <id> ...]");
    println!("  cargo run -p solarlab-conformance -- --list-scenarios");
}

fn cli_exit_code_for_report(report: &ConformanceReport) -> u8 {
    report_exit_code(report)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failing_reports_return_exit_code_one() {
        let report = ConformanceReport {
            schema_version: REPORT_SCHEMA_VERSION,
            commit_sha: None,
            selected_scenarios: vec!["failing-scenario".to_owned()],
            passed: false,
            summary: ConformanceSummary {
                total: 1,
                passed: 0,
                failed: 1,
            },
            scenarios: vec![ScenarioReport {
                id: "failing-scenario",
                family: "test",
                description: "Synthetic failing scenario for CLI exit code coverage.",
                passed: false,
                metrics: json!({}),
                thresholds: json!({}),
                notes: Vec::new(),
            }],
        };

        assert_eq!(cli_exit_code_for_report(&report), 1);
    }

    #[test]
    fn help_still_exits_successfully() {
        let args = vec!["--help".to_owned()];
        assert_eq!(run_with_args(args), Ok(0));
    }

    #[test]
    fn unknown_arguments_still_fail_invocation() {
        let args = vec!["--definitely-unknown".to_owned()];
        assert_eq!(
            run_with_args(args),
            Err("unknown argument: --definitely-unknown".to_owned())
        );
    }
}
