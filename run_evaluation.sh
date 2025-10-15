#!/bin/bash

# Evaluation script for preprocessing performance testing
# Tests all combinations of event logs, databases, modes, and systems

set -e

# Parse command line arguments
TIMEOUT_ENABLED=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --timeout)
            TIMEOUT_ENABLED=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--timeout]"
            exit 1
            ;;
    esac
done

# Configuration
EVENT_LOGS=("bpic2017" "bpic2018" "bpic2019")
DATABASES=("s3" "cassandra")
MODES=("positions" "timestamps")
SYSTEMS=("siesta" "set-containment" "signatures")
RESULTS_DIR="/app/evaluation_results"

# Create results directory
mkdir -p "$RESULTS_DIR"

echo "Starting evaluation experiments..."
if [ "$TIMEOUT_ENABLED" = true ]; then
    echo "Timeout enabled: Jobs will be terminated after 30 minutes"
fi

# Calculate total combinations considering the conditional database logic
total_combinations=0
for system in "${SYSTEMS[@]}"; do
    if [ "$system" = "siesta" ]; then
        total_combinations=$((total_combinations + ${#EVENT_LOGS[@]} * ${#DATABASES[@]} * ${#MODES[@]}))
    else
        total_combinations=$((total_combinations + ${#EVENT_LOGS[@]} * 1 * ${#MODES[@]}))  # Only cassandra
    fi
done

echo "Testing $total_combinations total combinations across ${#SYSTEMS[@]} systems"

# Function to extract timing from preprocess output
extract_timing() {
    local output="$1"
    # Extract the last line that contains timing information starting with "Time taken"
    local timing_line=$(echo "$output" | grep -i "^Time taken" | tail -n 1)

    # If no "Time taken" pattern found, try other timing patterns
    if [ -z "$timing_line" ]; then
        timing_line=$(echo "$output" | grep -i -E "(time|duration|elapsed|took)" | tail -n 1)
    fi

    # If still no timing pattern found, try to extract the last numeric value from the output
    if [ -z "$timing_line" ]; then
        local numeric_value=$(echo "$output" | grep -oE '[0-9]+(\.[0-9]+)?' | tail -n 1)
        if [ -n "$numeric_value" ]; then
            timing_line="Time taken (alt): $numeric_value"
        fi
    fi

    echo "$timing_line"
}

# Function to run preprocess for a specific batch file
run_preprocess_batch() {
    local log_name="$1"
    local database="$2"
    local mode="$3"
    local batch_file="$4"
    local results_file="$5"
    local system="$6"

    echo "Processing batch: $batch_file with database=$database, mode=$mode, system=$system"

    # Run spark-submit with the batch file
    # Optimized for CONCURRENT execution with Scylla on 12 cores / 64GB RAM
    # Spark: 6 cores, 30g driver memory (leaving space for Scylla + OS)
    # Scylla: 4 cores, 24GB (runs concurrently)
    # OS: 2 cores, 8GB reserved
    local output
    local spark_cmd="/opt/spark/bin/spark-submit \
        --master local[4] \
        --driver-memory 28g \
        --conf spark.driver.memoryOverhead=2g \
        --conf spark.sql.adaptive.enabled=true \
        --conf spark.sql.adaptive.coalescePartitions.enabled=true \
        --conf spark.eventLog.enabled=false \
        --conf spark.eventLog.dir=/tmp/spark-events \
        preprocess.jar \
        --logname \"$log_name\" \
        --file \"$batch_file\" \
        --database \"$database\" \
        --mode \"$mode\" \
        --system \"$system\""

    if [ "$TIMEOUT_ENABLED" = true ]; then
        # Run with 30-minute timeout
        output=$(timeout 1800 bash -c "$spark_cmd" 2>&1 || echo "TIMEOUT_EXCEEDED")
        if [[ "$output" == *"TIMEOUT_EXCEEDED"* ]]; then
            echo "Batch $(basename "$batch_file") timed out after 30 minutes"
            echo "Time taken: TIMEOUT" >> "$results_file"
            return
        fi
    else
        # Run without timeout
        output=$(bash -c "$spark_cmd" 2>&1)
    fi

    local timing
    timing=$(extract_timing "$output")

    if [ -n "$timing" ]; then
        echo "$timing" >> "$results_file"
        echo "Batch $(basename "$batch_file") completed: $timing"
    else
        echo "Warning: Could not extract timing from batch $(basename "$batch_file")"
        echo "Time taken: 0" >> "$results_file"  # Default value if timing extraction fails
    fi
}

# Main evaluation loop
for system in "${SYSTEMS[@]}"; do
    echo "Processing system: $system"

    for log_name in "${EVENT_LOGS[@]}"; do
        echo "  Processing event log: $log_name"

        for database in "${DATABASES[@]}"; do
            # Conditional database usage: siesta uses s3 and cassandra, others use only cassandra
            if [ "$system" != "siesta" ] && [ "$database" = "s3" ]; then
                echo "  Skipping $database for $system"
                continue
            fi

            for mode in "${MODES[@]}"; do
                echo "    Testing combination: $log_name + $database + $mode"

                # Create results file for this combination
                results_file="$RESULTS_DIR/${log_name}_${database}_${mode}_${system}_timings.txt"
                echo "# Timing results for $log_name with database=$database and mode=$mode (System: $system)" > "$results_file"
                echo "# Each line represents the processing time for one batch" >> "$results_file"

                total_time=0
                batch_count=0

                # Process all batches for this event log
                for batch_file in /app/output/${log_name}_*.withTimestamp; do
                    if [ -f "$batch_file" ]; then
                        logname=$log_name$mode
                        run_preprocess_batch "$logname" "$database" "$mode" "$batch_file" "$results_file" "$system"

                        # Read the last timing value and add to total
                        last_timing=$(tail -n 1 "$results_file" | grep -v "^#" | grep -oE '[0-9]+(\.[0-9]+)?')
                        if [ -n "$last_timing" ]; then
                            total_time=$(echo "$total_time + $last_timing" | bc -l)
                        fi
                        batch_count=$((batch_count + 1))
                    fi
                done

                # Add summary to results file
                echo "" >> "$results_file"
                echo "# Summary:" >> "$results_file"
                echo "# Total batches processed: $batch_count" >> "$results_file"
                echo "# Total processing time: $total_time" >> "$results_file"
                echo "# Average time per batch: $(echo "scale=4; $total_time / $batch_count" | bc -l)" >> "$results_file"

                echo "    Completed: $batch_count batches, total time: $total_time"
            done
        done
    done
done

echo "Evaluation completed! Results saved in $RESULTS_DIR"
echo "Generated files:"
ls -la "$RESULTS_DIR"/*.txt
