/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// JNI bridge to the whole-track DSP analyzer and its resampler.
//
// The result crosses as JSON; see analyzer/analysis_json.h, which the iOS C
// bridge shares, for why and for what is emitted.

#include <jni.h>

#include <string>
#include <vector>

#include "analyzer/analysis_json.h"
#include "analyzer/audio_analysis.h"
#include "analyzer/resampler.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_music_bitchord_playback_smart_TrackFeatures_nativeAnalyze(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble sample_rate,
    jdouble duration) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const bitchord::smart::AnalysisResult result =
      bitchord::smart::AnalyzeAudio(input, sample_rate, duration);

  const std::string json = bitchord::smart::json::ToJson(result);
  return env->NewStringUTF(json.c_str());
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_smart_TrackFeatures_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  // The rate the analyzer's window and hop constants assume.
  return 11025.0;
}

// Converts mono float PCM to the analyzer's rate. Separate from nativeAnalyze
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_smart_TrackFeatures_nativeResample(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble input_rate,
    jdouble output_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const std::vector<float> resampled =
      bitchord::smart::Resample(input, input_rate, output_rate);

  const jsize produced = static_cast<jsize>(resampled.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;  // OOM; the exception is already pending.
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, resampled.data());
  }
  return result;
}

}  // extern "C"
