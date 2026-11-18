fn main() {
    // Android NDK r27 does not select 16 KiB alignment by default. Set both
    // segment and RELRO page sizes without replacing user/toolchain RUSTFLAGS.
    // This is a packaging compatibility contract, not a performance claim.
    println!("cargo:rerun-if-env-changed=CARGO_CFG_TARGET_OS");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,common-page-size=16384");
    }
}
