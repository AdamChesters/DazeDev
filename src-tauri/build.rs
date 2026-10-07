use std::{
    env, fs,
    path::{Path, PathBuf},
};
fn copy_libraries(source: &Path, destination: &Path) {
    let mut count = 0;
    for entry in fs::read_dir(source).expect("native runtime directory") {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy();
        if name.ends_with(".dll")
            || name.ends_with(".dylib")
            || name.ends_with(".so")
            || name.contains(".so.")
        {
            fs::copy(&path, destination.join(name.as_ref())).expect("copy native runtime");
            count += 1;
        }
    }
    assert!(count > 0, "Native runtime directory contains no libraries");
}
fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        if let Some(sdk) = env::var_os("VULKAN_SDK") {
            println!(
                "cargo:rustc-link-search=native={}",
                Path::new(&sdk).join("Lib").display()
            );
        }
    }
    if let Some(runtime) = env::var_os("DEP_TRANSCRIBE_CPP_RUNTIME_DIR") {
        let destination = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("transcribe-libs");
        fs::create_dir_all(&destination).unwrap();
        copy_libraries(Path::new(&runtime), &destination);
        if let Some(modules) = env::var_os("DEP_TRANSCRIBE_CPP_MODULE_DIR") {
            copy_libraries(Path::new(&modules), &destination);
        }
    }
    #[cfg(feature = "desktop")]
    tauri_build::build()
}
