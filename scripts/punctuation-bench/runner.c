// CPU-only inference and replay for the pinned token classifiers, on Linux or Android.
#define _POSIX_C_SOURCE 200809L
#include "onnxruntime_c_api.h"
#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

enum { MAX_PIECES = 160 };
typedef struct {
    int64_t ids[MAX_PIECES];
    size_t count;
} Window;

static const OrtApi *ort;
static void check(OrtStatus *status) {
    if (status) {
        fprintf(stderr, "%s\n", ort->GetErrorMessage(status));
        ort->ReleaseStatus(status);
        exit(1);
    }
}
static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}
static long memory_kib(const char *field) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long value = -1;
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, field, strlen(field))) {
            value = strtol(line + strlen(field), NULL, 10);
            break;
        }
    }
    fclose(f);
    return value;
}
static Window *load_windows(const char *path, size_t *count) {
    FILE *f = fopen(path, "r");
    if (!f) { perror(path); exit(1); }
    size_t capacity = 0, bytes = 0;
    Window *windows = NULL;
    char *line = NULL;
    while (getline(&line, &bytes, f) >= 0) {
        if (*count == capacity) {
            capacity = capacity ? capacity * 2 : 8;
            windows = realloc(windows, capacity * sizeof(*windows));
            if (!windows) exit(1);
        }
        Window *w = &windows[(*count)++];
        w->count = 0;
        char *p = line;
        for (;;) {
            p += strspn(p, " \r\t\n");
            if (!*p) break;
            char *end;
            errno = 0;
            long id = strtol(p, &end, 10);
            if (errno || end == p || id < 0 || w->count == MAX_PIECES) exit(2);
            w->ids[w->count++] = id;
            p = end;
        }
        if (!w->count) exit(2);
    }
    free(line);
    fclose(f);
    if (!*count) exit(2);
    return windows;
}
static void infer(OrtSession *session, OrtMemoryInfo *mem, Window *w, size_t input_count, FILE *logits) {
    static const char *const names[] = {"input_ids", "attention_mask", "token_type_ids"};
    static const char *const outputs[] = {"logits"};
    int64_t mask[MAX_PIECES], types[MAX_PIECES] = {0};
    for (size_t i = 0; i < w->count; ++i) mask[i] = 1;
    int64_t *arrays[] = {w->ids, mask, types};
    int64_t shape[] = {1, (int64_t)w->count};
    OrtValue *inputs[3], *out = NULL;
    for (size_t i = 0; i < input_count; ++i)
        check(ort->CreateTensorWithDataAsOrtValue(mem, arrays[i], w->count * sizeof(int64_t),
                shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[i]));
    check(ort->Run(session, NULL, names, (const OrtValue *const *)inputs, input_count, outputs, 1, &out));
    if (logits) {
        OrtTensorTypeAndShapeInfo *info;
        size_t elements;
        check(ort->GetTensorTypeAndShape(out, &info));
        check(ort->GetTensorShapeElementCount(info, &elements));
        ort->ReleaseTensorTypeAndShapeInfo(info);
        if (!elements || elements % w->count != 0) exit(2);
        float *data;
        check(ort->GetTensorMutableData(out, (void **)&data));
        if (fwrite(data, sizeof(float), elements, logits) != elements) exit(1);
    }
    ort->ReleaseValue(out);
    for (size_t i = 0; i < input_count; ++i) ort->ReleaseValue(inputs[i]);
}
int main(int argc, char **argv) {
    if (argc != 5 && argc != 6) {
        fprintf(stderr, "usage: runner MODEL WINDOWS THREADS REPEATS [FLOAT32_LOGITS]\n");
        return 2;
    }
    int threads = atoi(argv[3]), repeats = atoi(argv[4]);
    if (threads < 1 || threads > 8 || repeats < 0 || repeats > 1000) return 2;
    size_t count = 0;
    Window *windows = load_windows(argv[2], &count);
    const OrtApiBase *base = OrtGetApiBase();
    ort = base->GetApi(ORT_API_VERSION);
    if (!ort) return 2;
    long initial = memory_kib("VmRSS:");
    double start = now_ms();
    OrtEnv *env;
    OrtSessionOptions *opts;
    OrtSession *session;
    OrtMemoryInfo *mem;
    check(ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "rupunct", &env));
    check(ort->CreateSessionOptions(&opts));
    check(ort->SetIntraOpNumThreads(opts, threads));
    check(ort->SetInterOpNumThreads(opts, 1));
    check(ort->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL));
    check(ort->CreateSession(env, argv[1], opts, &session));
    check(ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem));
    size_t input_count;
    check(ort->SessionGetInputCount(session, &input_count));
    if (input_count != 2 && input_count != 3) return 2;
    printf("runtime %s\nbase_kib %ld\nload_ms %.3f\n", base->GetVersionString(), initial, now_ms() - start);
    for (int pass = 0; pass <= repeats; ++pass) {
        start = now_ms();
        for (size_t i = 0; i < count; ++i) infer(session, mem, &windows[i], input_count, NULL);
        printf("%s %.3f\n", pass ? "warm_ms" : "first_ms", now_ms() - start);
    }
    printf("rss_kib %ld\nhwm_kib %ld\n", memory_kib("VmRSS:"), memory_kib("VmHWM:"));
    if (argc == 6) {
        FILE *out = fopen(argv[5], "wb");
        if (!out) return 1;
        for (size_t i = 0; i < count; ++i) infer(session, mem, &windows[i], input_count, out);
        if (fclose(out)) return 1;
    }
    ort->ReleaseMemoryInfo(mem);
    ort->ReleaseSession(session);
    ort->ReleaseSessionOptions(opts);
    ort->ReleaseEnv(env);
    free(windows);
    return 0;
}
