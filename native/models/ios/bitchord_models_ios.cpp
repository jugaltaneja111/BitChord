/*
 * Copyright (C) 2026 Kushagra Singh
 *
 * Part of BitChord, licensed under the GNU General Public License, version 3
 * or later.
 */

// The iOS app's way to run Automix's two models, Beat This! (beats and
// downbeats) and open-unmix (vocals): a plain C ABI over ONNX Runtime's C API,
// built into BitChordModels.framework (iosApp/project.yml) with Microsoft's
// prebuilt onnxruntime.xcframework linked in statically
// (scripts/fetch-onnxruntime.sh). Android and the desktop run the same models
// through ai.onnxruntime instead.
//
// Bound at run time by iosKit's IosModels with dlopen/dlsym, as
// BitChordAnalysis is: Kotlin/Native cannot run cinterop for Apple targets on
// the Linux host that compiles iosKit. The signatures are restated there, and
// kAbiVersion tells a mismatch apart from a missing library.
//
// One float32 input, every output read back as float32. Sessions run with one
// intra-op thread and every graph optimisation, and without ORT's arena, which
// keeps every block it has ever allocated for the session's life: analysis
// runs a handful of times per track, in a music player that must stay small in
// the background. Nothing here throws into the caller.

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <onnxruntime/onnxruntime_c_api.h>

#define BITCHORD_EXPORT extern "C" __attribute__((visibility("default"), used))

namespace {

/** Bumped whenever a signature below changes; IosModels refuses any other. */
constexpr int32_t kAbiVersion = 1;

const OrtApi* Api() {
  static const OrtApi* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  return api;
}

thread_local std::string last_error;

/** True, with [last_error] set from [status], when [status] is a failure; releases it either way. */
bool Failed(OrtStatus* status) {
  if (status == nullptr) return false;
  const char* message = Api()->GetErrorMessage(status);
  last_error = message != nullptr ? message : "onnxruntime failed";
  Api()->ReleaseStatus(status);
  return true;
}

OrtEnv* Env() {
  static OrtEnv* env = [] {
    OrtEnv* made = nullptr;
    if (Failed(Api()->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "BitChord", &made))) return static_cast<OrtEnv*>(nullptr);
    return made;
  }();
  return env;
}

struct Session {
  OrtSession* session = nullptr;
  std::string input;
  std::vector<std::string> outputs;
};

void Release(Session* s) {
  if (s == nullptr) return;
  if (s->session != nullptr) Api()->ReleaseSession(s->session);
  delete s;
}

}  // namespace

BITCHORD_EXPORT int32_t bcm_abi(void) { return kAbiVersion; }

/** The calling thread's last failure, or an empty string; valid until its next call. */
BITCHORD_EXPORT const char* bcm_last_error(void) { return last_error.c_str(); }

/**
 * Opens the model at [path] with [threads] intra-op threads; a session for
 * bcm_run and bcm_close, or null (see bcm_last_error).
 */
BITCHORD_EXPORT void* bcm_open(const char* path, int32_t threads) {
  last_error.clear();
  if (path == nullptr) {
    last_error = "no model path";
    return nullptr;
  }
  try {
    const OrtApi* api = Api();
    OrtEnv* env = Env();
    if (api == nullptr || env == nullptr) {
      if (last_error.empty()) last_error = "onnxruntime would not start";
      return nullptr;
    }
    OrtSessionOptions* options = nullptr;
    if (Failed(api->CreateSessionOptions(&options))) return nullptr;
    bool ok = !Failed(api->SetIntraOpNumThreads(options, threads > 0 ? threads : 1)) &&
              !Failed(api->SetInterOpNumThreads(options, 1)) &&
              !Failed(api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) &&
              !Failed(api->DisableCpuMemArena(options)) &&
              !Failed(api->DisableMemPattern(options));
    auto* s = new Session();
    if (ok) ok = !Failed(api->CreateSession(env, path, options, &s->session));
    api->ReleaseSessionOptions(options);
    if (!ok) {
      Release(s);
      return nullptr;
    }
    OrtAllocator* allocator = nullptr;
    if (Failed(api->GetAllocatorWithDefaultOptions(&allocator))) {
      Release(s);
      return nullptr;
    }
    char* name = nullptr;
    if (Failed(api->SessionGetInputName(s->session, 0, allocator, &name))) {
      Release(s);
      return nullptr;
    }
    s->input = name;
    allocator->Free(allocator, name);
    size_t count = 0;
    if (Failed(api->SessionGetOutputCount(s->session, &count)) || count == 0) {
      if (last_error.empty()) last_error = "the model has no outputs";
      Release(s);
      return nullptr;
    }
    for (size_t i = 0; i < count; ++i) {
      if (Failed(api->SessionGetOutputName(s->session, i, allocator, &name))) {
        Release(s);
        return nullptr;
      }
      s->outputs.emplace_back(name);
      allocator->Free(allocator, name);
    }
    return s;
  } catch (...) {
    last_error = "out of memory opening the model";
    return nullptr;
  }
}

/** How many outputs [session] has; 0 for a null one. */
BITCHORD_EXPORT int32_t bcm_output_count(void* session) {
  auto* s = static_cast<Session*>(session);
  return s == nullptr ? 0 : static_cast<int32_t>(s->outputs.size());
}

/**
 * Runs [session] on [input], a float32 tensor of [rank] dimensions [shape].
 * Each output's values go into a malloc'd buffer at outputs[i] (the caller
 * frees it with bcm_free) with its length at lengths[i], for up to
 * [max_outputs]; the number written, or -1 (see bcm_last_error).
 */
BITCHORD_EXPORT int32_t bcm_run(
    void* session,
    const float* input,
    const int64_t* shape,
    int32_t rank,
    float** outputs,
    int64_t* lengths,
    int32_t max_outputs) {
  last_error.clear();
  auto* s = static_cast<Session*>(session);
  if (s == nullptr || input == nullptr || shape == nullptr || rank <= 0 || outputs == nullptr || lengths == nullptr) {
    last_error = "bad arguments";
    return -1;
  }
  const OrtApi* api = Api();
  int64_t elements = 1;
  for (int32_t i = 0; i < rank; ++i) elements *= shape[i];
  if (elements <= 0) {
    last_error = "empty input";
    return -1;
  }
  OrtMemoryInfo* memory = nullptr;
  if (Failed(api->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &memory))) return -1;
  OrtValue* tensor = nullptr;
  // ORT reads the caller's buffer where it lies; it is not written to.
  const bool made = !Failed(api->CreateTensorWithDataAsOrtValue(
      memory, const_cast<float*>(input), static_cast<size_t>(elements) * sizeof(float), shape,
      static_cast<size_t>(rank), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor));
  api->ReleaseMemoryInfo(memory);
  if (!made) return -1;

  const size_t count = s->outputs.size();
  std::vector<const char*> names;
  names.reserve(count);
  for (const auto& name : s->outputs) names.push_back(name.c_str());
  std::vector<OrtValue*> results(count, nullptr);
  const char* input_name = s->input.c_str();
  const OrtValue* inputs[] = {tensor};
  const bool ran = !Failed(api->Run(s->session, nullptr, &input_name, inputs, 1, names.data(), count, results.data()));
  api->ReleaseValue(tensor);

  int32_t written = 0;
  bool ok = ran;
  for (size_t i = 0; i < count; ++i) {
    OrtValue* value = results[i];
    if (value == nullptr) continue;
    if (ok && written < max_outputs) {
      OrtTensorTypeAndShapeInfo* info = nullptr;
      size_t size = 0;
      float* data = nullptr;
      if (Failed(api->GetTensorTypeAndShape(value, &info))) {
        ok = false;
      } else {
        ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
        ok = !Failed(api->GetTensorElementType(info, &type)) && !Failed(api->GetTensorShapeElementCount(info, &size));
        api->ReleaseTensorTypeAndShapeInfo(info);
        if (ok && type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
          last_error = "an output is not float32";
          ok = false;
        }
      }
      if (ok) ok = !Failed(api->GetTensorMutableData(value, reinterpret_cast<void**>(&data)));
      if (ok) {
        float* copy = static_cast<float*>(std::malloc((size > 0 ? size : 1) * sizeof(float)));
        if (copy == nullptr) {
          last_error = "out of memory reading the output";
          ok = false;
        } else {
          if (size > 0) std::memcpy(copy, data, size * sizeof(float));
          outputs[written] = copy;
          lengths[written] = static_cast<int64_t>(size);
          ++written;
        }
      }
    }
    api->ReleaseValue(value);
  }
  if (!ok) {
    for (int32_t i = 0; i < written; ++i) std::free(outputs[i]);
    if (last_error.empty()) last_error = "inference failed";
    return -1;
  }
  return written;
}

/** Releases an output bcm_run made; null is fine. */
BITCHORD_EXPORT void bcm_free(float* buffer) { std::free(buffer); }

/** Closes a session bcm_open made; null is fine. */
BITCHORD_EXPORT void bcm_close(void* session) { Release(static_cast<Session*>(session)); }
