# SIESTA Experiments Runner

This script automates the execution of SIESTA preprocessing experiments across multiple configurations and datasets, collecting detailed execution time measurements.

## Overview

The `run_experiments.sh` script runs experiments with different combinations of:
- **Datasets**: BPIC2017, BPIC2018, BPIC2019 (XES format)
- **Databases**: S3 and Cassandra 
- **Modes**: Timestamps and Positions

By default, this results in **12 total experiments** (3 datasets × 2 databases × 2 modes).

## Prerequisites

1. **Datasets**: Place the following XES files in `./experiments/input/`:
   - `bpic2017.xes`
   - `bpic2018.xes` 
   - `bpic2019.xes`

2. **Environment**: Ensure your environment is properly configured:
   - S3/MinIO instance running (for S3 experiments)
   - Cassandra instance running (for Cassandra experiments)
   - Environment variables set (see main README.md)

3. **Build**: The script will automatically build the project if needed

## Usage

### Basic Usage (All Configurations)
```bash
./run_experiments.sh
```
This runs all 12 experiments (3 datasets × 2 databases × 2 modes).

### Custom Configurations

#### Test specific databases only:
```bash
# Test only S3
./run_experiments.sh --databases "s3"

# Test only Cassandra  
./run_experiments.sh --databases "cassandra"
```

#### Test specific modes only:
```bash
# Test only timestamps mode
./run_experiments.sh --modes "timestamps"

# Test only positions mode
./run_experiments.sh --modes "positions"
```

#### Test specific datasets only:
```bash
# Test only one dataset
./run_experiments.sh --datasets "bpic2017.xes"

# Test multiple specific datasets
./run_experiments.sh --datasets "bpic2017.xes,bpic2019.xes"
```

#### Combined custom configuration:
```bash
# Test S3 with timestamps mode on specific datasets
./run_experiments.sh \
    --datasets "bpic2017.xes,bpic2018.xes" \
    --databases "s3" \
    --modes "timestamps"
```

### Other Options

#### Clean previous results:
```bash
./run_experiments.sh --clean
```

#### Custom log name prefix:
```bash
./run_experiments.sh --logname-prefix "my_experiment"
```

#### Help:
```bash
./run_experiments.sh --help
```

## Output

### Results File
Results are saved to `./experiments/output/experiment_results.txt` with detailed information for each experiment:

```
Dataset: bpic2017.xes
Database: s3
Mode: timestamps
Log Name: experiment_bpic2017_s3_timestamps
File Size: 1.2GB
Start Time: 2025-10-01 14:30:15 (1696169415123)
End Time: 2025-10-01 14:45:32 (1696170332456)
Execution Time (ms): 917333
Exit Code: 0
Status: SUCCESS
----------------------------------------
```

### Console Output
The script provides real-time progress updates with:
- Colored logging (INFO, SUCCESS, WARNING, ERROR)
- Current experiment progress (e.g., "Experiment 5/12")
- Configuration details for each run
- Summary statistics at the end

### Summary Statistics
At the end, the script displays:
- Execution times grouped by configuration
- Total execution time across all experiments
- Success/failure counts
- Average execution time for successful runs

## Example Output

```
[INFO] SIESTA Experiments Runner
[INFO] Project directory: /path/to/SequenceDetectionPreprocess
[INFO] Datasets: bpic2017.xes bpic2018.xes bpic2019.xes
[INFO] Databases: s3 cassandra
[INFO] Modes: timestamps positions

=== Experiment 1/12 ===
[INFO] Dataset: bpic2017.xes, Database: s3, Mode: timestamps
[INFO] File size: 1.2GB
[SUCCESS] Experiment completed for bpic2017.xes (s3, timestamps) in 917333ms

...

=== EXECUTION TIMES BY CONFIGURATION ===

--- Database: s3, Mode: timestamps ---
  Dataset: bpic2017.xes
  Execution Time (ms): 917333
  Status: SUCCESS

--- Database: s3, Mode: positions ---
  Dataset: bpic2017.xes  
  Execution Time (ms): 856421
  Status: SUCCESS

=== SUMMARY STATISTICS ===
Total Execution Time: 10456789ms
Successful Experiments: 11
Failed Experiments: 1  
Total Experiments: 12
Average Execution Time (successful): 950617ms
```

## Testing

A test script is provided to verify functionality with smaller datasets:

```bash
./test_experiments.sh
```

This runs a single experiment with existing test data to verify the script works correctly.

## Configuration Matrix

| Dataset | Database | Mode | Log Name Example |
|---------|----------|------|------------------|
| bpic2017.xes | s3 | timestamps | experiment_bpic2017_s3_timestamps |
| bpic2017.xes | s3 | positions | experiment_bpic2017_s3_positions |
| bpic2017.xes | cassandra | timestamps | experiment_bpic2017_cassandra_timestamps |
| bpic2017.xes | cassandra | positions | experiment_bpic2017_cassandra_positions |
| bpic2018.xes | s3 | timestamps | experiment_bpic2018_s3_timestamps |
| ... | ... | ... | ... |

## Troubleshooting

### Missing Datasets
```
[ERROR] Missing datasets in ./experiments/input:
  - bpic2017.xes
```
**Solution**: Download and place the XES files in the `experiments/input` directory.

### Build Failures
If the build fails, ensure:
- Java and SBT are properly installed
- Environment variables are set correctly
- Dependencies are available

### Database Connection Issues
- **S3**: Verify MinIO/S3 is running and accessible
- **Cassandra**: Verify Cassandra is running and accessible

### Out of Memory
For large datasets, you may need to increase JVM memory:
```bash
export JAVA_OPTS="-Xmx8g -Xms4g"
./run_experiments.sh
```