// A command line copy of what the measuring app does, to find out where it fails.
// usage: rxflow <key: a0|zero> <dataset: 0|1> <vm: light|fast> <secure: 0|1> <threads> <hashes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>
#include "randomx.h"

static void fill_input(unsigned char* in, uint32_t thread, uint64_t nonce) {
    for (int i = 0; i < 80; i++) in[i] = static_cast<unsigned char>(i * 7 + 1);
    std::memcpy(in + 68, &thread, 4);
    std::memcpy(in + 72, &nonce, 8);
}

int main(int argc, char** argv) {
    if (argc < 7) { std::printf("usage: rxflow key dataset vm secure threads hashes\n"); return 2; }
    const bool a0 = std::strcmp(argv[1], "a0") == 0;
    const bool with_dataset = std::atoi(argv[2]) != 0;
    const bool fast = std::strcmp(argv[3], "fast") == 0;
    const bool secure = std::atoi(argv[4]) != 0;
    const int threads = std::atoi(argv[5]);
    const int hashes = std::atoi(argv[6]);
    randomx_flags flags = randomx_get_flags() | RANDOMX_FLAG_V2;
    if (secure) flags |= RANDOMX_FLAG_SECURE;
    std::printf("flags %d\n", static_cast<int>(flags)); std::fflush(stdout);
    randomx_cache* cache = randomx_alloc_cache(flags);
    if (!cache) { std::printf("no cache\n"); return 1; }
    unsigned char key[32];
    for (int i = 0; i < 32; i++) key[i] = a0 ? static_cast<unsigned char>(0xA0 + i) : 0;
    randomx_init_cache(cache, key, sizeof(key));
    std::printf("cache ready\n"); std::fflush(stdout);
    randomx_dataset* dataset = nullptr;
    if (with_dataset) {
        dataset = randomx_alloc_dataset(RANDOMX_FLAG_DEFAULT);
        if (!dataset) { std::printf("no dataset\n"); return 1; }
        const unsigned long items = randomx_dataset_item_count();
        std::vector<std::thread> init;
        for (int i = 0; i < threads; i++) {
            const unsigned long start = items / threads * i;
            const unsigned long count = (i == threads - 1) ? items - start : items / threads;
            init.emplace_back([=] { randomx_init_dataset(dataset, cache, start, count); });
        }
        for (auto& t : init) t.join();
        std::printf("dataset ready\n"); std::fflush(stdout);
    }
    randomx_flags vmflags = flags;
    if (fast && dataset) vmflags |= RANDOMX_FLAG_FULL_MEM;
    randomx_vm* vm = randomx_create_vm(vmflags, cache, (fast && dataset) ? dataset : nullptr);
    if (!vm) { std::printf("no vm\n"); return 1; }
    std::printf("vm ready\n"); std::fflush(stdout);
    unsigned char in[80], out[RANDOMX_HASH_SIZE];
    for (int n = 0; n < hashes; n++) {
        fill_input(in, 0xFFFFFFFFu, 0x0123456789ABCDEFull + n);
        randomx_calculate_hash(vm, in, sizeof(in), out);
        if (n == 0) { for (int i = 0; i < RANDOMX_HASH_SIZE; i++) std::printf("%02x", out[i]); std::printf("\n"); std::fflush(stdout); }
    }
    randomx_destroy_vm(vm);
    std::printf("done %d\n", hashes);
    return 0;
}
