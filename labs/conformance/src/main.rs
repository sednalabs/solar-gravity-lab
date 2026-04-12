use std::env;
use std::process::ExitCode;

use solarlab_conformance::{report_exit_code, run_report, scenario_ids};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("{message}");
            ExitCode::from(2)
        }
    }
}

fn run() -> Result<(), String> {
    let mut args = env::args().skip(1);
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
                return Ok(());
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
        return Ok(());
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
    if report_exit_code(&report) == 0 {
        Ok(())
    } else {
        Err("one or more conformance scenarios failed".to_owned())
    }
}

fn print_help() {
    println!("solarlab-conformance");
    println!();
    println!("Usage:");
    println!("  cargo run -p solarlab-conformance -- [--pretty] [--scenario <id> ...]");
    println!("  cargo run -p solarlab-conformance -- --list-scenarios");
}
