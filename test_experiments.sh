#!/bin/bash

# Quick test of the experiment script with existing test data
# This script tests the functionality before running on large BPIC datasets

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "Testing experiment script with existing test data..."

# Test with test1.withTimestamp which already exists
./run_experiments.sh \
    --datasets "test1.withTimestamp" \
    --databases "s3" \
    --modes "timestamps" \
    --logname-prefix "test" \
    --clean

echo ""
echo "Test completed. Check experiments/output/experiment_results.txt for results."