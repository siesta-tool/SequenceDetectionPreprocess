#!/bin/bash

# SIESTA Experiments Runner
# This script runs experiments on BPIC datasets and collects execution times

set -e  # Exit on any error

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Configuration
EXPERIMENTS_INPUT_DIR="./experiments/input"
EXPERIMENTS_OUTPUT_DIR="./experiments/output"
RESULTS_FILE="$EXPERIMENTS_OUTPUT_DIR/experiment_results.txt"
BUILD_SCRIPT="./build-and-run.sh"

# Datasets to process
DATASETS=("bpic2017.xes" "bpic2018.xes" "bpic2019.xes")

# Database and mode configurations to test
DATABASES=("s3" "cassandra")
MODES=("timestamps" "positions")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "This script runs SIESTA preprocessing experiments on BPIC datasets."
    echo ""
    echo "Options:"
    echo "  --help               Show this help message"
    echo "  --clean              Clean previous results before running"
    echo "  --datasets           Override datasets (comma-separated, e.g., \"bpic2017.xes,bpic2018.xes\")"
    echo "  --databases          Override databases (comma-separated, e.g., \"s3,cassandra\", default: \"s3,cassandra\")"
    echo "  --modes              Override modes (comma-separated, e.g., \"timestamps,positions\", default: \"timestamps,positions\")"
    echo "  --logname-prefix     Prefix for log names (default: experiment)"
    echo ""
    echo "Default configurations tested:"
    echo "  Databases: s3, cassandra"
    echo "  Modes: timestamps, positions"
    echo "  This results in 4 experiments per dataset (2 databases × 2 modes)"
    echo ""
    echo "Required datasets in $EXPERIMENTS_INPUT_DIR:"
    echo "  - bpic2017.xes"
    echo "  - bpic2018.xes" 
    echo "  - bpic2019.xes"
    echo ""
    echo "Results will be saved to: $RESULTS_FILE"
}

# Function to check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check if build script exists
    if [[ ! -f "$BUILD_SCRIPT" ]]; then
        log_error "Build script not found: $BUILD_SCRIPT"
        exit 1
    fi
    
    # Check if experiments directories exist
    if [[ ! -d "$EXPERIMENTS_INPUT_DIR" ]]; then
        log_error "Experiments input directory not found: $EXPERIMENTS_INPUT_DIR"
        exit 1
    fi
    
    # Create output directory if it doesn't exist
    if [[ ! -d "$EXPERIMENTS_OUTPUT_DIR" ]]; then
        log_info "Creating experiments output directory: $EXPERIMENTS_OUTPUT_DIR"
        mkdir -p "$EXPERIMENTS_OUTPUT_DIR"
    fi
    
    log_success "Prerequisites check completed"
}

# Function to verify datasets
verify_datasets() {
    log_info "Verifying datasets..."
    
    local missing_datasets=()
    for dataset in "${DATASETS[@]}"; do
        if [[ ! -f "$EXPERIMENTS_INPUT_DIR/$dataset" ]]; then
            missing_datasets+=("$dataset")
        fi
    done
    
    if [[ ${#missing_datasets[@]} -gt 0 ]]; then
        log_error "Missing datasets in $EXPERIMENTS_INPUT_DIR:"
        for dataset in "${missing_datasets[@]}"; do
            echo "  - $dataset"
        done
        log_error "Please place the required XES files in the input directory"
        exit 1
    fi
    
    log_success "All datasets found"
}

# Function to get file size in a human-readable format
get_file_size() {
    local file_path="$1"
    if command -v numfmt &> /dev/null; then
        local size_bytes=$(stat -c%s "$file_path")
        numfmt --to=iec-i --suffix=B "$size_bytes"
    else
        ls -lh "$file_path" | awk '{print $5}'
    fi
}

# Function to run experiment for a single dataset with specific database and mode
run_experiment() {
    local dataset="$1"
    local database="$2"
    local mode="$3"
    local logname_prefix="$4"
    local dataset_path="$EXPERIMENTS_INPUT_DIR/$dataset"
    local logname="${logname_prefix}_$(basename "$dataset" .xes)_${database}_${mode}"
    
    log_info "Running experiment for dataset: $dataset"
    log_info "Database: $database, Mode: $mode"
    log_info "Dataset path: $dataset_path"
    log_info "Log name: $logname"
    log_info "File size: $(get_file_size "$dataset_path")"
    
    # Record start time in milliseconds
    local start_time=$(date +%s%3N)
    local start_time_human=$(date '+%Y-%m-%d %H:%M:%S')
    
    log_info "Started at: $start_time_human"
    
    # Run the preprocessing with time measurement
    local exit_code=0
    if ! "$BUILD_SCRIPT" --file "$dataset_path" --logname "$logname" --database "$database" --mode "$mode" --delete_prev; then
        exit_code=$?
        log_error "Experiment failed for dataset: $dataset, database: $database, mode: $mode (exit code: $exit_code)"
        
        # Write failure results to file
        local end_time=$(date +%s%3N)
        local end_time_human=$(date '+%Y-%m-%d %H:%M:%S')
        local execution_time=$((end_time - start_time))
        
        {
            echo "Dataset: $dataset"
            echo "Database: $database"
            echo "Mode: $mode"
            echo "Log Name: $logname"
            echo "File Size: $(get_file_size "$dataset_path")"
            echo "Start Time: $start_time_human ($start_time)"
            echo "End Time: $end_time_human ($end_time)"
            echo "Execution Time (ms): $execution_time"
            echo "Exit Code: $exit_code"
            echo "Status: FAILED"
            echo "----------------------------------------"
        } >> "$RESULTS_FILE"
        
        return $exit_code
    fi
    
    # Record end time in milliseconds
    local end_time=$(date +%s%3N)
    local end_time_human=$(date '+%Y-%m-%d %H:%M:%S')
    local execution_time=$((end_time - start_time))
    
    log_info "Completed at: $end_time_human"
    log_success "Experiment completed for $dataset ($database, $mode) in ${execution_time}ms"
    
    # Write results to file
    {
        echo "Dataset: $dataset"
        echo "Database: $database"
        echo "Mode: $mode"
        echo "Log Name: $logname"
        echo "File Size: $(get_file_size "$dataset_path")"
        echo "Start Time: $start_time_human ($start_time)"
        echo "End Time: $end_time_human ($end_time)"
        echo "Execution Time (ms): $execution_time"
        echo "Exit Code: $exit_code"
        echo "Status: SUCCESS"
        echo "----------------------------------------"
    } >> "$RESULTS_FILE"
    
    return 0
}

# Function to initialize results file
init_results_file() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    {
        echo "========================================"
        echo "SIESTA Experiments Results"
        echo "Timestamp: $timestamp"
        echo "Project Directory: $PROJECT_DIR"
        echo "Datasets: ${DATASETS[*]}"
        echo "Databases: ${DATABASES[*]}"
        echo "Modes: ${MODES[*]}"
        echo "Total Configurations: $((${#DATASETS[@]} * ${#DATABASES[@]} * ${#MODES[@]}))"
        echo "========================================"
        echo ""
    } > "$RESULTS_FILE"
    
    log_info "Results will be logged to: $RESULTS_FILE"
}

# Function to summarize results
summarize_results() {
    log_info "Experiment Summary:"
    
    if [[ -f "$RESULTS_FILE" ]]; then
        echo ""
        echo "=== EXECUTION TIMES BY CONFIGURATION ==="
        
        # Show results grouped by configuration
        for database in "${DATABASES[@]}"; do
            for mode in "${MODES[@]}"; do
                echo ""
                echo "--- Database: $database, Mode: $mode ---"
                grep -A 15 "Database: $database" "$RESULTS_FILE" | grep -A 15 "Mode: $mode" | grep "Dataset:\|Execution Time (ms):\|Status:" | sed 's/^/  /'
            done
        done
        
        echo ""
        echo "=== SUMMARY STATISTICS ==="
        
        # Calculate statistics
        local total_time=0
        local successful_count=0
        local failed_count=0
        
        while read -r time; do
            total_time=$((total_time + time))
        done < <(grep "Execution Time (ms):" "$RESULTS_FILE" | awk '{print $4}')
        
        successful_count=$(grep -c "Status: SUCCESS" "$RESULTS_FILE" 2>/dev/null || echo 0)
        failed_count=$(grep -c "Status: FAILED" "$RESULTS_FILE" 2>/dev/null || echo 0)
        
        echo "Total Execution Time: ${total_time}ms"
        echo "Successful Experiments: $successful_count"
        echo "Failed Experiments: $failed_count"
        echo "Total Experiments: $((successful_count + failed_count))"
        
        if [[ $successful_count -gt 0 ]]; then
            local avg_time=$((total_time / successful_count))
            echo "Average Execution Time (successful): ${avg_time}ms"
        fi
        
        echo ""
        
        log_success "Full results available in: $RESULTS_FILE"
    else
        log_warning "Results file not found"
    fi
}

# Main function
main() {
    local clean_results=false
    local logname_prefix="experiment"
    local custom_datasets=""
    local custom_databases=""
    local custom_modes=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --help)
                show_usage
                exit 0
                ;;
            --clean)
                clean_results=true
                shift
                ;;
            --datasets)
                custom_datasets="$2"
                shift 2
                ;;
            --databases)
                custom_databases="$2"
                shift 2
                ;;
            --modes)
                custom_modes="$2"
                shift 2
                ;;
            --logname-prefix)
                logname_prefix="$2"
                shift 2
                ;;
            *)
                log_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done
    
    # Override configurations if custom ones provided
    if [[ -n "$custom_datasets" ]]; then
        IFS=',' read -ra DATASETS <<< "$custom_datasets"
        log_info "Using custom datasets: ${DATASETS[*]}"
    fi
    
    if [[ -n "$custom_databases" ]]; then
        IFS=',' read -ra DATABASES <<< "$custom_databases"
        log_info "Using custom databases: ${DATABASES[*]}"
    fi
    
    if [[ -n "$custom_modes" ]]; then
        IFS=',' read -ra MODES <<< "$custom_modes"
        log_info "Using custom modes: ${MODES[*]}"
    fi
    
    log_info "SIESTA Experiments Runner"
    log_info "Project directory: $PROJECT_DIR"
    log_info "Log name prefix: $logname_prefix"
    log_info "Datasets: ${DATASETS[*]}"
    log_info "Databases: ${DATABASES[*]}"
    log_info "Modes: ${MODES[*]}"
    
    # Clean previous results if requested
    if $clean_results && [[ -f "$RESULTS_FILE" ]]; then
        log_info "Cleaning previous results..."
        rm -f "$RESULTS_FILE"
    fi
    
    # Run checks
    check_prerequisites
    verify_datasets
    
    # Initialize results file
    init_results_file
    
    # Calculate total experiments
    local total_experiments=$((${#DATASETS[@]} * ${#DATABASES[@]} * ${#MODES[@]}))
    local current_experiment=1
    local failed_experiments=0
    
    log_info "Starting $total_experiments experiments..."
    
    # Run experiments for all combinations
    for dataset in "${DATASETS[@]}"; do
        for database in "${DATABASES[@]}"; do
            for mode in "${MODES[@]}"; do
                echo ""
                log_info "=== Experiment $current_experiment/$total_experiments ==="
                log_info "Dataset: $dataset, Database: $database, Mode: $mode"
                
                if ! run_experiment "$dataset" "$database" "$mode" "$logname_prefix"; then
                    failed_experiments=$((failed_experiments + 1))
                fi
                
                current_experiment=$((current_experiment + 1))
            done
        done
    done
    
    echo ""
    log_info "=== EXPERIMENTS COMPLETED ==="
    
    # Show summary
    summarize_results
    
    # Final status
    if [[ $failed_experiments -eq 0 ]]; then
        log_success "All experiments completed successfully!"
        exit 0
    else
        log_error "$failed_experiments experiments failed"
        exit 1
    fi
}

# Run main function with all arguments
main "$@"