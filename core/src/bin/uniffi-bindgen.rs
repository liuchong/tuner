//! uniffi-bindgen 命令行入口（生成 Kotlin/Swift 绑定用）。
//! 用法见 `scripts/build-core-android.sh`。

fn main() {
    uniffi::uniffi_bindgen_main();
}
