#!/bin/bash
# User-mode end-to-end rehearsal. No sudo, no locking.
set -e
cd "$(dirname "$0")/../.."
exec jolt -M:e2e
