#!/usr/bin/env bash
set -euo pipefail
cargo fmt
cargo fmt --check
cargo clippy -j 2 --all-targets -- -D warnings
cargo test -j 2 --doc
