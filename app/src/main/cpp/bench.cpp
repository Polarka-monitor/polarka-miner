// Polarka Miner: the part that computes TrueNorth's proof of work.
// RandomX is taken unchanged from the TrueNorth sources (src/randomx) and used the way the project's own
// wrapper uses it: the flags the processor recommends plus the v2 program format, one VM per thread.
// Two uses: measuring (a made-up header, no hash goes anywhere) and mining (a header from the node's block
// template; a nonce whose hash is at or below the target is handed back to the app, which sends the block).
// No keys and no network here.
#include <jni.h>
#include <sched.h>
#include <sys/mman.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "randomx.h"

namespace {
randomx_cache* g_cache = nullptr;
randomx_dataset* g_dataset = nullptr;
std::atomic<bool> g_stop{true};
std::atomic<uint64_t> g_hashes{0};
std::atomic<int> g_failed{0};
std::vector<std::thread> g_workers;
// Building, using and freeing the cache and the dataset never overlap: every call that does one of them
// holds this lock. A call to release therefore waits until a dataset that is being built is finished.
std::mutex g_life;

// The job being mined. Workers copy it when its number changes. Number 0 means: nothing to do.
struct Job { unsigned char header[76]; unsigned char target[32]; uint64_t id; };
std::mutex g_job_mutex;
Job g_job{};
std::atomic<uint64_t> g_job_id{0};
// Found nonces wait here until the app takes them.
struct Found { uint64_t job; uint32_t nonce; };
std::mutex g_found_mutex;
std::vector<Found> g_found;

// Some systems refuse memory that is writable and executable at once. RandomX ignores that refusal and
// would then run code from a page that may not be executed. So it is tried once on a spare page; where
// it is refused, RandomX is told to switch its code pages between writing and running instead.
bool rwx_allowed() {
    static const int answer = [] {
        void* p = mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) return 0;
        const int ok = mprotect(p, 4096, PROT_READ | PROT_WRITE | PROT_EXEC) == 0 ? 1 : 0;
        munmap(p, 4096);
        return ok;
    }();
    return answer == 1;
}

randomx_flags base_flags() {
    randomx_flags flags = randomx_get_flags() | RANDOMX_FLAG_V2;
    if ((flags & RANDOMX_FLAG_JIT) && !rwx_allowed()) flags |= RANDOMX_FLAG_SECURE;
    return flags;
}

void stop_workers() {
    g_stop = true;
    for (auto& t : g_workers) if (t.joinable()) t.join();
    g_workers.clear();
}

void release_all() {
    stop_workers();
    if (g_dataset) { randomx_release_dataset(g_dataset); g_dataset = nullptr; }
    if (g_cache) { randomx_release_cache(g_cache); g_cache = nullptr; }
}

std::string hex(const unsigned char* p, size_t n) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (size_t i = 0; i < n; i++) { s += d[p[i] >> 4]; s += d[p[i] & 15]; }
    return s;
}

// The hash and the target are 256-bit numbers stored least significant byte first, as in the node.
bool meets_target(const unsigned char* hash, const unsigned char* target) {
    for (int i = 31; i >= 0; i--) {
        if (hash[i] < target[i]) return true;
        if (hash[i] > target[i]) return false;
    }
    return true;
}

void pin(int cpu) {
    if (cpu < 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

randomx_vm* new_vm() {
    if (!g_cache) return nullptr;
    randomx_flags flags = base_flags();
    if (g_dataset) flags |= RANDOMX_FLAG_FULL_MEM;
    return randomx_create_vm(flags, g_cache, g_dataset);
}

void fill_input(unsigned char* in, uint32_t thread, uint64_t nonce) {
    for (int i = 0; i < 80; i++) in[i] = static_cast<unsigned char>(i * 7 + 1);
    std::memcpy(in + 68, &thread, 4);
    std::memcpy(in + 72, &nonce, 8);
}

// Measuring: hashes of a made-up header, as fast as the thread can.
void bench_worker(int index, int cpu, bool pipelined) {
    pin(cpu);
    randomx_vm* vm = new_vm();
    if (!vm) { g_failed++; return; }
    unsigned char in[80], out[RANDOMX_HASH_SIZE];
    uint64_t nonce = 0;
    if (pipelined) {
        fill_input(in, index, nonce++);
        randomx_calculate_hash_first(vm, in, sizeof(in));
        while (!g_stop.load(std::memory_order_relaxed)) {
            fill_input(in, index, nonce++);
            randomx_calculate_hash_next(vm, in, sizeof(in), out);
            g_hashes.fetch_add(1, std::memory_order_relaxed);
        }
    } else {
        while (!g_stop.load(std::memory_order_relaxed)) {
            fill_input(in, index, nonce++);
            randomx_calculate_hash(vm, in, sizeof(in), out);
            g_hashes.fetch_add(1, std::memory_order_relaxed);
        }
    }
    randomx_destroy_vm(vm);
}

// Mining: thread number `index` of `count` tries the nonces index, index + count, index + 2 * count, ...
// of the current job, the way the reference miner splits them. A new job starts the count again.
void mine_worker(int index, int count, int cpu) {
    pin(cpu);
    randomx_vm* vm = new_vm();
    if (!vm) { g_failed++; return; }
    Job job{};
    uint64_t nonce = 0;
    bool idle = true;
    unsigned char in[80], out[RANDOMX_HASH_SIZE];
    while (!g_stop.load(std::memory_order_relaxed)) {
        if (g_job_id.load(std::memory_order_acquire) != job.id) {
            std::lock_guard<std::mutex> lock(g_job_mutex);
            job = g_job;
            nonce = static_cast<uint64_t>(index);
            idle = job.id == 0;
            std::memcpy(in, job.header, 76);
        }
        if (idle) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }
        const uint32_t n = static_cast<uint32_t>(nonce);
        in[76] = static_cast<unsigned char>(n);
        in[77] = static_cast<unsigned char>(n >> 8);
        in[78] = static_cast<unsigned char>(n >> 16);
        in[79] = static_cast<unsigned char>(n >> 24);
        randomx_calculate_hash(vm, in, sizeof(in), out);
        g_hashes.fetch_add(1, std::memory_order_relaxed);
        if (meets_target(out, job.target)) {
            std::lock_guard<std::mutex> lock(g_found_mutex);
            g_found.push_back(Found{job.id, n});
        }
        nonce += static_cast<uint64_t>(count);
        if (nonce > 0xFFFFFFFFull) idle = true;  // every nonce of this job tried; wait for the next job
    }
    randomx_destroy_vm(vm);
}

void set_job(const unsigned char* header, const unsigned char* target, uint64_t id) {
    std::lock_guard<std::mutex> lock(g_job_mutex);
    if (header && target && id != 0) {
        std::memcpy(g_job.header, header, 76);
        std::memcpy(g_job.target, target, 32);
        g_job.id = id;
    } else {
        g_job.id = 0;
    }
    g_job_id.store(g_job.id, std::memory_order_release);
}
}  // namespace

extern "C" {

// Builds the cache (256 MiB) from the key and, when fast is asked for, the dataset (about 2 GiB) with the
// given number of threads. A missing key means the fixed key of the measuring mode. Returns a line that says
// what was built, or one that starts with "error".
JNIEXPORT jstring JNICALL Java_org_polarka_miner_Native_prepare(JNIEnv* env, jclass, jbyteArray jkey, jboolean fast, jint threads) {
    std::lock_guard<std::mutex> life(g_life);
    release_all();
    set_job(nullptr, nullptr, 0);
    const randomx_flags flags = base_flags();
    char line[256];
    auto t0 = std::chrono::steady_clock::now();
    g_cache = randomx_alloc_cache(flags);
    if (!g_cache) return env->NewStringUTF("error: the cache could not be allocated");
    unsigned char key[32];
    for (int i = 0; i < 32; i++) key[i] = static_cast<unsigned char>(0xA0 + i);
    if (jkey != nullptr) {
        if (env->GetArrayLength(jkey) != 32) return env->NewStringUTF("error: the key must be 32 bytes");
        env->GetByteArrayRegion(jkey, 0, 32, reinterpret_cast<jbyte*>(key));
    }
    randomx_init_cache(g_cache, key, sizeof(key));
    double cache_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    double data_s = 0;
    if (fast) {
        g_dataset = randomx_alloc_dataset(RANDOMX_FLAG_DEFAULT);
        if (!g_dataset) {
            std::snprintf(line, sizeof(line), "error: the dataset could not be allocated (flags %d, cache %.1f s)", static_cast<int>(flags), cache_s);
            return env->NewStringUTF(line);
        }
        auto t1 = std::chrono::steady_clock::now();
        const unsigned long items = randomx_dataset_item_count();
        const int n = threads < 1 ? 1 : threads;
        std::vector<std::thread> init;
        for (int i = 0; i < n; i++) {
            const unsigned long start = items / n * i;
            const unsigned long count = (i == n - 1) ? items - start : items / n;
            init.emplace_back([start, count] { randomx_init_dataset(g_dataset, g_cache, start, count); });
        }
        for (auto& t : init) t.join();
        data_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
    }
    std::snprintf(line, sizeof(line), "ok: %s, flags %d (jit %d, hard aes %d, v2 %d, secure %d), cache %.1f s, dataset %.1f s",
                  fast ? "fast" : "light", static_cast<int>(flags), (flags & RANDOMX_FLAG_JIT) ? 1 : 0,
                  (flags & RANDOMX_FLAG_HARD_AES) ? 1 : 0, (flags & RANDOMX_FLAG_V2) ? 1 : 0,
                  (flags & RANDOMX_FLAG_SECURE) ? 1 : 0, cache_s, data_s);
    return env->NewStringUTF(line);
}

// The hash of one fixed input with the key of the measuring mode. The same on every machine.
JNIEXPORT jstring JNICALL Java_org_polarka_miner_Native_selfHash(JNIEnv* env, jclass, jboolean withDataset) {
    std::lock_guard<std::mutex> life(g_life);
    if (!g_cache) return env->NewStringUTF("error: nothing prepared");
    randomx_flags flags = base_flags();
    if (withDataset && g_dataset) flags |= RANDOMX_FLAG_FULL_MEM;
    randomx_vm* vm = randomx_create_vm(flags, g_cache, (withDataset && g_dataset) ? g_dataset : nullptr);
    if (!vm) return env->NewStringUTF("error: no vm");
    unsigned char in[80], out[RANDOMX_HASH_SIZE];
    fill_input(in, 0xFFFFFFFFu, 0x0123456789ABCDEFull);
    randomx_calculate_hash(vm, in, sizeof(in), out);
    randomx_destroy_vm(vm);
    return env->NewStringUTF(hex(out, sizeof(out)).c_str());
}

// The proof-of-work hash of an 80-byte block header with the prepared key, as lowercase hex in the byte
// order RandomX gives (least significant byte first). Used for checks, not for mining.
JNIEXPORT jstring JNICALL Java_org_polarka_miner_Native_hashHeader(JNIEnv* env, jclass, jbyteArray jheader) {
    std::lock_guard<std::mutex> life(g_life);
    if (!g_cache) return env->NewStringUTF("error: nothing prepared");
    if (jheader == nullptr || env->GetArrayLength(jheader) != 80) return env->NewStringUTF("error: the header must be 80 bytes");
    unsigned char in[80], out[RANDOMX_HASH_SIZE];
    env->GetByteArrayRegion(jheader, 0, 80, reinterpret_cast<jbyte*>(in));
    randomx_vm* vm = new_vm();
    if (!vm) return env->NewStringUTF("error: no vm");
    randomx_calculate_hash(vm, in, sizeof(in), out);
    randomx_destroy_vm(vm);
    return env->NewStringUTF(hex(out, sizeof(out)).c_str());
}

JNIEXPORT jboolean JNICALL Java_org_polarka_miner_Native_meetsTarget(JNIEnv* env, jclass, jbyteArray jhash, jbyteArray jtarget) {
    if (jhash == nullptr || jtarget == nullptr || env->GetArrayLength(jhash) != 32 || env->GetArrayLength(jtarget) != 32) return JNI_FALSE;
    unsigned char h[32], t[32];
    env->GetByteArrayRegion(jhash, 0, 32, reinterpret_cast<jbyte*>(h));
    env->GetByteArrayRegion(jtarget, 0, 32, reinterpret_cast<jbyte*>(t));
    return meets_target(h, t) ? JNI_TRUE : JNI_FALSE;
}

// Measuring: one worker for each entry of cpus; an entry of -1 leaves the thread where the system puts it.
JNIEXPORT void JNICALL Java_org_polarka_miner_Native_start(JNIEnv* env, jclass, jintArray cpus, jboolean pipelined) {
    std::lock_guard<std::mutex> life(g_life);
    if (!g_cache) return;
    stop_workers();
    g_hashes = 0;
    g_failed = 0;
    g_stop = false;
    const jsize n = env->GetArrayLength(cpus);
    jint* c = env->GetIntArrayElements(cpus, nullptr);
    for (jsize i = 0; i < n; i++) g_workers.emplace_back(bench_worker, static_cast<int>(i), static_cast<int>(c[i]), pipelined == JNI_TRUE);
    env->ReleaseIntArrayElements(cpus, c, JNI_ABORT);
}

// Mining: one worker for each entry of cpus. They wait until setJob gives them a header.
JNIEXPORT void JNICALL Java_org_polarka_miner_Native_mineStart(JNIEnv* env, jclass, jintArray cpus) {
    std::lock_guard<std::mutex> life(g_life);
    if (!g_cache) return;
    stop_workers();
    g_hashes = 0;
    g_failed = 0;
    { std::lock_guard<std::mutex> lock(g_found_mutex); g_found.clear(); }
    g_stop = false;
    const jsize n = env->GetArrayLength(cpus);
    jint* c = env->GetIntArrayElements(cpus, nullptr);
    for (jsize i = 0; i < n; i++) g_workers.emplace_back(mine_worker, static_cast<int>(i), static_cast<int>(n), static_cast<int>(c[i]));
    env->ReleaseIntArrayElements(cpus, c, JNI_ABORT);
}

// The header without its nonce (76 bytes), the target (32 bytes, least significant first) and a number for
// the job that is not 0. A missing header or the number 0 makes the workers wait.
JNIEXPORT void JNICALL Java_org_polarka_miner_Native_setJob(JNIEnv* env, jclass, jbyteArray jheader, jbyteArray jtarget, jlong id) {
    if (jheader == nullptr || jtarget == nullptr || id == 0 || env->GetArrayLength(jheader) != 76 || env->GetArrayLength(jtarget) != 32) {
        set_job(nullptr, nullptr, 0);
        return;
    }
    unsigned char h[76], t[32];
    env->GetByteArrayRegion(jheader, 0, 76, reinterpret_cast<jbyte*>(h));
    env->GetByteArrayRegion(jtarget, 0, 32, reinterpret_cast<jbyte*>(t));
    set_job(h, t, static_cast<uint64_t>(id));
}

// Nonces found since the last call, as pairs (job number, nonce); null when there are none.
JNIEXPORT jlongArray JNICALL Java_org_polarka_miner_Native_takeFound(JNIEnv* env, jclass) {
    std::vector<Found> found;
    { std::lock_guard<std::mutex> lock(g_found_mutex); found.swap(g_found); }
    if (found.empty()) return nullptr;
    std::vector<jlong> flat;
    for (const Found& f : found) { flat.push_back(static_cast<jlong>(f.job)); flat.push_back(static_cast<jlong>(f.nonce)); }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(flat.size()));
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}

JNIEXPORT jlong JNICALL Java_org_polarka_miner_Native_hashes(JNIEnv*, jclass) { return static_cast<jlong>(g_hashes.load()); }
JNIEXPORT jint JNICALL Java_org_polarka_miner_Native_failed(JNIEnv*, jclass) { return g_failed.load(); }
JNIEXPORT void JNICALL Java_org_polarka_miner_Native_stop(JNIEnv*, jclass) { std::lock_guard<std::mutex> life(g_life); stop_workers(); set_job(nullptr, nullptr, 0); }
JNIEXPORT void JNICALL Java_org_polarka_miner_Native_release(JNIEnv*, jclass) { std::lock_guard<std::mutex> life(g_life); release_all(); set_job(nullptr, nullptr, 0); }

}  // extern "C"
