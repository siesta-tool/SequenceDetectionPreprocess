#!/bin/bash

# SIESTA Build and Run Script
# This script builds the project (if not already built) and runs it with provided arguments

set -e  # Exit on any error

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Configuration
MAIN_CLASS="auth.datalab.siesta.siesta_main"
JAR_NAME="sequencedetectionpreprocess-assembly-3.0.0.jar"
TARGET_DIR="target/scala-2.12"
JAR_PATH="$TARGET_DIR/$JAR_NAME"
BUILD_FILE="build.sbt"

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

# Function to set up environment variables
setup_environment_variables() {
    log_info "Setting up environment variables..."
    
    # S3/MinIO Configuration
    export s3accessKeyAws="${S3_ACCESS_KEY:-minioadmin}"
    export s3secretKeyAws="${S3_SECRET_KEY:-minioadmin}"
    export s3endPointLoc="${S3_ENDPOINT:-http://localhost:9000}"
    export s3ConnectionTimeout="${S3_CONNECTION_TIMEOUT:-60000}"
    
    # Cassandra Configuration
    export CASSANDRA_HOST="${CASSANDRA_HOST:-localhost}"
    export CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
    export CASSANDRA_USER="${CASSANDRA_USER:-cassandra}"
    export CASSANDRA_PASSWORD="${CASSANDRA_PASSWORD:-cassandra}"

    # PostgreSQL Configuration
    export POSTGRES_ENDPOINT="${POSTGRES_ENDPOINT:-localhost:5432}"
    export POSTGRES_USERNAME="${POSTGRES_USERNAME:-postgres}"
    export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
    
    # Kafka Configuration
    export kafkaBroker="${KAFKA_BROKER:-localhost:9092}"
    export kafkaTopic="${KAFKA_TOPIC:-siesta-events}"
    
    # Java 17+ compatibility options for Spark
    export JAVA_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.lang.invoke=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.io=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.net=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.nio=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.util=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.nio.cs=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.security.action=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
    export JAVA_OPTS="$JAVA_OPTS --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
    
    log_success "Environment variables configured"
    
    # Print configuration if verbose mode is enabled
    if [[ "${VERBOSE:-false}" == "true" ]]; then
        log_info "Current configuration:"
        echo "  S3/MinIO:"
        echo "    Endpoint: $s3endPointLoc"
        echo "    Access Key: $s3accessKeyAws"
        echo "    Secret Key: [HIDDEN]"
        echo "    Connection Timeout: $s3ConnectionTimeout"
        echo "  Cassandra:"
        echo "    Host: $CASSANDRA_HOST"
        echo "    Port: $CASSANDRA_PORT"
        echo "    User: $CASSANDRA_USER"
        echo "    Password: [HIDDEN]"
        echo "  PostgreSQL:"
        echo "    Endpoint: $POSTGRES_ENDPOINT"
        echo "    Username: $POSTGRES_USERNAME"
        echo "    Password: [HIDDEN]"
        echo "  Kafka:"
        echo "    Broker: $kafkaBroker"
        echo "    Topic: $kafkaTopic"
        echo "  Java Options:"
        echo "    JAVA_OPTS: $JAVA_OPTS"
    fi
}

# Function to check if build is needed
needs_build() {
    # Check if JAR exists
    if [[ ! -f "$JAR_PATH" ]]; then
        log_info "JAR file not found at $JAR_PATH"
        return 0  # true - needs build
    fi
    
    # Check if any source files are newer than the JAR
    local jar_timestamp=$(stat -c %Y "$JAR_PATH" 2>/dev/null || echo 0)
    
    # Check build.sbt
    if [[ -f "$BUILD_FILE" && $(stat -c %Y "$BUILD_FILE") -gt $jar_timestamp ]]; then
        log_info "build.sbt is newer than JAR"
        return 0  # true - needs build
    fi
    
    # Check src directory
    if [[ -d "src" ]]; then
        local newest_src=$(find src -type f -name "*.scala" -o -name "*.java" | xargs stat -c %Y 2>/dev/null | sort -nr | head -1)
        if [[ -n "$newest_src" && "$newest_src" -gt $jar_timestamp ]]; then
            log_info "Source files are newer than JAR"
            return 0  # true - needs build
        fi
    fi
    
    return 1  # false - no build needed
}

# Function to build the project
build_project() {
    log_info "Building project..."
    
    # Check if sbt is available
    if ! command -v sbt &> /dev/null; then
        log_error "sbt is not installed or not in PATH"
        log_error "Please install sbt: https://www.scala-sbt.org/download.html"
        exit 1
    fi
    
    # Run sbt assembly
    log_info "Running 'sbt assembly'..."
    if sbt assembly; then
        log_success "Build completed successfully"
        
        # Verify JAR was created
        if [[ -f "$JAR_PATH" ]]; then
            log_success "JAR created at $JAR_PATH"
        else
            log_error "Build completed but JAR not found at expected location: $JAR_PATH"
            exit 1
        fi
    else
        log_error "Build failed"
        exit 1
    fi
}

# Function to run the application
run_application() {
    local args="$@"
    
    log_info "Running application with arguments: $args"
    
    # Check if java is available
    if ! command -v java &> /dev/null; then
        log_error "java is not installed or not in PATH"
        exit 1
    fi
    
    # Run the JAR with provided arguments and Java options
    log_info "Executing: java $JAVA_OPTS -jar $JAR_PATH $args"
    java $JAVA_OPTS -jar "$JAR_PATH" "$@"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "This script builds the SIESTA preprocessing component (if needed) and runs it."
    echo ""
    echo "Build behavior:"
    echo "  - Builds only if JAR doesn't exist or source files are newer than JAR"
    echo "  - Uses 'sbt assembly' to create the JAR"
    echo ""
    echo "Environment Variables:"
    echo "  S3/MinIO Configuration:"
    echo "    S3_ACCESS_KEY        S3 access key (default: minioadmin)"
    echo "    S3_SECRET_KEY        S3 secret key (default: minioadmin)"
    echo "    S3_ENDPOINT          S3 endpoint URL (default: http://localhost:9000)"
    echo "    S3_CONNECTION_TIMEOUT Connection timeout in ms (default: 60000)"
    echo ""
    echo "  Cassandra Configuration:"
    echo "    CASSANDRA_HOST       Cassandra host (default: localhost)"
    echo "    CASSANDRA_PORT       Cassandra port (default: 9042)"
    echo "    CASSANDRA_USER       Cassandra username (default: cassandra)"
    echo "    CASSANDRA_PASSWORD   Cassandra password (default: cassandra)"
    echo ""
    echo "  PostgreSQL Configuration:"
    echo "    POSTGRES_ENDPOINT    PostgreSQL endpoint (default: localhost:5432)"
    echo "    POSTGRES_USERNAME    PostgreSQL username (default: postgres)"
    echo "    POSTGRES_PASSWORD    PostgreSQL password (default: postgres)"
    echo ""
    echo "  Kafka Configuration:"
    echo "    KAFKA_BROKER         Kafka broker (default: localhost:9092)"
    echo "    KAFKA_TOPIC          Kafka topic (default: siesta-events)"
    echo ""
    echo "  Script Configuration:"
    echo "    VERBOSE              Show detailed configuration (default: false)"
    echo ""
    echo "Note: Java 17+ compatibility options are automatically configured for Spark."
    echo ""
    echo "Common SIESTA options:"
    echo "  --system <system>        System for indexing"
    echo "  -d, --database <database> Database to store the index"
    echo "  -m, --mode <mode>        Use timestamps or positions in indexing"
    echo "  -f, --file <file>        Input file (if not set, generates artificial data)"
    echo "  --logname <logname>      Name of the index to be created"
    echo "  --delete_all             Clean all tables in the keyspace"
    echo ""
    echo "Examples:"
    echo "  $0 --help                           # Show SIESTA help"
    echo "  $0 --script-help                    # Show this script help"
    echo "  $0 --system s3 --database postgresql --file input.log"
    echo "  $0 --mode timestamp --traces 1000"
    echo ""
    echo "  # Using custom S3 endpoint:"
    echo "  S3_ENDPOINT=http://my-minio:9000 S3_ACCESS_KEY=mykey S3_SECRET_KEY=mysecret $0 --system s3 --file input.log"
    echo ""
    echo "  # Using custom Cassandra configuration:"
    echo "  CASSANDRA_HOST=my-cassandra CASSANDRA_USER=myuser CASSANDRA_PASSWORD=mypass $0 --database cassandra"
    echo ""
    echo "  # Enable verbose output to see configuration:"
    echo "  VERBOSE=true $0 --system s3 --file input.log"
    echo ""
    echo "Script options:"
    echo "  --force-build            Force rebuild even if not needed"
    echo "  --build-only             Only build, don't run"
    echo "  --script-help            Show this help message"
    echo "  --show-env               Show current environment configuration and exit"
}

# Main script logic
main() {
    local force_build=false
    local build_only=false
    local app_args=()
    
    # Parse script-specific arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --force-build)
                force_build=true
                shift
                ;;
            --build-only)
                build_only=true
                shift
                ;;
            --script-help)
                show_usage
                exit 0
                ;;
            --show-env)
                export VERBOSE=true
                setup_environment_variables
                exit 0
                ;;
            *)
                # All other arguments are passed to the application
                app_args+=("$1")
                shift
                ;;
        esac
    done
    
    log_info "SIESTA Build and Run Script"
    log_info "Project directory: $PROJECT_DIR"
    
    # Set up environment variables
    setup_environment_variables
    
    # Check if we need to build
    if $force_build || needs_build; then
        build_project
    else
        log_success "JAR is up to date, skipping build"
    fi
    
    # If build-only flag is set, exit after building
    if $build_only; then
        log_success "Build completed. Use --help to see run options."
        exit 0
    fi
    
    # Run the application
    run_application "${app_args[@]}"
}

# Run main function with all arguments
main "$@"