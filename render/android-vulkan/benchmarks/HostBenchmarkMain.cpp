#include "../src/main/cpp/BenchmarkNative.h"
#include <iostream>
#include <string>

// The same entry points used by the APK, for software Vulkan correctness proof.
// Hosted timings are never promoted to physical-device performance evidence.
int main(int argc, char **argv) {
    try {
        if (argc == 1)
            std::cout << solarlab::benchmark::Capabilities() << '\n';
        else
            std::cout << solarlab::benchmark::RunCase(argv[1], argc > 2 ? std::stoi(argv[2]) : 1) << '\n';
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
