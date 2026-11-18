//! A small host/native qualification entry point with the same JNI payloads.
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args == ["--catalogue"] {
        println!("{}", solarlab_benchmarks::catalogue_json());
        return;
    }
    fn argument<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
        args.windows(2)
            .find(|pair| pair[0] == name)
            .map(|pair| pair[1].as_str())
    }
    let Some(case) = argument(&args, "--case") else {
        eprintln!("usage: research --catalogue | --case ID --fixture ID [--steps 8] [--repeats 1]");
        std::process::exit(2);
    };
    let fixture = argument(&args, "--fixture").unwrap_or("sparse3");
    let steps = argument(&args, "--steps")
        .unwrap_or("8")
        .parse()
        .unwrap_or(0);
    let repeats = argument(&args, "--repeats")
        .unwrap_or("1")
        .parse()
        .unwrap_or(0);
    let result = solarlab_benchmarks::run_case_json(case, fixture, steps, repeats);
    println!("{result}");
    let parsed: serde_json::Value =
        serde_json::from_str(&result).expect("research API returns valid JSON");
    if parsed["status"] == "FAIL" {
        std::process::exit(1);
    }
}
