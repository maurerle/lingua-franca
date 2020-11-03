/**
 * @file
 * @author Edward A. Lee
 *
 * @section LICENSE
Copyright (c) 2020, The University of California at Berkeley and TU Dresden

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 * @section DESCRIPTION
 * Standalone program to convert a Lingua Franca trace file to a comma-separated values
 * text file.
 */
#define LINGUA_FRANCA_TRACE
#include "reactor.h"
#include "trace.h"

// Mutex used to provent collisions between threads writing to the file.
pthread_mutex_t _lf_trace_mutex = PTHREAD_MUTEX_INITIALIZER;

/** Buffer for reading trace records. */
trace_record_t trace[TRACE_BUFFER_CAPACITY];

/** Buffer for reading object descriptions. Size limit is BUFFER_SIZE bytes. */
#define BUFFER_SIZE 1024
char buffer[BUFFER_SIZE];

/**
 * Print a usage message.
 */
void usage() {
    printf("\nUsage: trace_to_csv [options] trace_file_root (without .lft extension)\n\n");
    /* No options yet:
    printf("\nOptions: \n\n");
    printf("  -f, --fast [true | false]\n");
    printf("   Whether to wait for physical time to match logical time.\n\n");
    printf("\n\n");
    */
}

/** The start time read from the trace file. */
instant_t start_time;

/** Table of pointers to a description of the object. */
// FIXME: Replace with hash table implementation.
object_description_t* object_descriptions;
int object_descriptions_size = 0;

/**
 * Get the object description corresponding to the specified pointer.
 * If there is no such object, return "NO DESCRIPTION FOUND".
 * @param object The pointer.
 */
char* get_description(void* object) {
    // FIXME: Replace with a hash table implementation.
    for (int i = 0; i < object_descriptions_size; i++) {
        if (object_descriptions[i].object == object) {
            return object_descriptions[i].description;
        }
    }
    return "NO DESCRIPTION FOUND";
}

/**
 * Print the object to description table.
 */
void print_table() {
    printf("------- objects traced:\n");
    for (int i = 0; i < object_descriptions_size; i++) {
        printf("%p: %s\n", object_descriptions[i].object, object_descriptions[i].description);
    } 
    printf("-------\n");
}

/**
 * Read header information.
 * @return The number of objects in the object table or -1 for failure.
 */
size_t read_header(FILE* trace_file) {
    // Read the start time.
    int items_read = fread(&start_time, sizeof(instant_t), 1, trace_file);
    if (items_read != 1) _LF_TRACE_FAILURE(trace_file);

    printf("Start time is %lld.\n", start_time);

    // Read the table mapping pointers to descriptions.
    // First read its length.
    items_read = fread(&object_descriptions_size, sizeof(int), 1, trace_file);
    if (items_read != 1) _LF_TRACE_FAILURE(trace_file);

    printf("There are %d objects traced.\n", object_descriptions_size);

    object_descriptions = calloc(object_descriptions_size, sizeof(trace_record_t));
    if (object_descriptions == NULL) {
        fprintf(stderr, "ERROR: Memory allocation failure %d.\n", errno);
        return -1;
    }

    // Next, read each table entry.
    for (int i = 0; i < object_descriptions_size; i++) {
        void* object;
        items_read = fread(&object, sizeof(void*), 1, trace_file);
        if (items_read != 1) _LF_TRACE_FAILURE(trace_file);
        object_descriptions[i].object = object;

        // Next, read the string description into the buffer.
        int description_length = 0;
        char character;
        items_read = fread(&character, sizeof(char), 1, trace_file);
        if (items_read != 1) _LF_TRACE_FAILURE(trace_file);
        while(character != 0 && description_length < BUFFER_SIZE - 1) {
            buffer[description_length++] = character;
            items_read = fread(&character, sizeof(char), 1, trace_file);
            if (items_read != 1) _LF_TRACE_FAILURE(trace_file);
        }
        // Terminate with null.
        buffer[description_length++] = 0;

        // Allocate memory to store the description.
        object_descriptions[i].description = malloc(description_length);
        strcpy(object_descriptions[i].description, buffer);
    }
    print_table();
    return object_descriptions_size;
}

/**
 * Read the trace and write to a CSV file.
 * @param trace_file The file to read.
 * @param csv_file The file to write.
 * @return The number of records read or 0 upon seeing an EOF.
 */
size_t read_trace(FILE* trace_file, FILE* csv_file) {
    // Read first the int giving the length of the trace.
    int trace_length;
    int items_read = fread(&trace_length, sizeof(int), 1, trace_file);
    if (items_read != 1) {
        if (feof(trace_file)) return 0;
        fprintf(stderr, "Failed to read trace length.\n");
        exit(3);
    }
    if (trace_length > TRACE_BUFFER_CAPACITY) {
        fprintf(stderr, "ERROR: Trace length %d exceeds capacity. File is garbled.\n", trace_length);
        exit(4);
    }
    // printf("DEBUG: Trace of length %d being converted.\n", trace_length);

    items_read += fread(&trace, sizeof(trace_record_t), trace_length, trace_file);
    // Write each line.
    for (int i = 0; i < trace_length; i++) {
        char* reaction_name = "none";
        if (trace[i].reaction_number >= 0) {
            reaction_name = (char*)malloc(4);
            snprintf(reaction_name, 4, "%d", trace[i].reaction_number);
        }
        // printf("DEBUG: self_struct pointer: %p\n", trace[i].self_struct);
        fprintf(csv_file, "%s, %s, %s, %d, %lld, %d, %lld\n",
                trace_event_names[trace[i].event_type],
                get_description(trace[i].self_struct),
                reaction_name,
                trace[i].worker,
                trace[i].logical_time - start_time,
                trace[i].microstep,
                trace[i].physical_time - start_time
        );
    }
    return items_read;
}

int main(int argc, char* argv[]) {
    if (argc != 2) {
        usage();
        exit(0);
    }
    // Open the input file for reading.
    char trace_file_name[strlen(argv[1]) + 4];
    strcpy(trace_file_name, argv[1]);
    strcat(trace_file_name, ".lft");
    FILE* trace_file = fopen(trace_file_name, "r");
    if (trace_file == NULL) {
        fprintf(stderr, "No trace file named %s.\n", trace_file_name);
        usage();
        exit(2);
    }

    // Open the output file for writing.
    char csv_file_name[strlen(argv[1]) + 4];
    strcpy(csv_file_name, argv[1]);
    strcat(csv_file_name, ".csv");
    FILE* csv_file = fopen(csv_file_name, "w");
    if (csv_file == NULL) {
        fprintf(stderr, "Could not create output file named %s.\n", csv_file_name);
        usage();
        exit(2);
    }

    if (read_header(trace_file) >= 0) {
        // Write a header line into the CSV file.
        fprintf(csv_file, "Event, Reactor, Reaction, Worker, Elapsed Logical Time, Microstep, Elapsed Physical Time\n");
        while (read_trace(trace_file, csv_file) != 0) {};
    }
    // Free memory in object description table.
    for (int i = 0; i < object_descriptions_size; i++) {
        free(object_descriptions[i].description);
    }
    fclose(trace_file);
    fclose(csv_file);
}
