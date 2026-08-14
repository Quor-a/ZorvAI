// quro_omni_video_stub.cpp
//
// 【QuroAI 构建补丁】为 MNN 上游 omni 引擎提供 Omni::qwenVideoProcess 的兜底定义。
//
// 背景：MNN 上游 transformers/llm/engine/src/omni.cpp 中的 videoProcess() 会调用
// Omni::qwenVideoProcess()。该符号的实际定义位于「仅在 LLM_SUPPORT_VISION 开启时
// 才参与编译」的翻译单元中（LLM_SUPPORT_VISION 由 MNN_BUILD_OPENCV=ON 触发）。
// 本项目通过 GitHub tarball（FetchContent）拉取 MNN，tarball 不含 OpenCV 子模块，
// 因此无法开启 MNN_BUILD_OPENCV，导致 qwenVideoProcess 的最终定义被整体排除编译，
// 而 videoProcess 仍引用它 → libMNN.so 链接报 undefined symbol。
//
// 本项目实际走云模型，本地离线引擎不依赖视频多模态，故此处提供语义与上游 #else 分支
// 一致的兜底实现：返回空 vector（即「无视频 token」）。这样 videoProcess 的调用得以
// 消解，本地引擎仅丢失视频多模态能力，不影响文本 / 图像（图像走独立 imageProcess 路径）。
//
// 实现说明：为不依赖 omni.hpp（其上仅 MNN 引擎内部 include 路径可见，且会拉入 cv/audio
// 等需要 OpenCV 的头），这里仅声明一个最小的 Omni 类 + 与上游完全一致的成员函数签名，
// 由链接器按 Itanium mangled name 归并到同一符号。该桩挂到 MNN 的 llm 目标
// （与 omni.cpp.o 同一链接单元 libMNN.so），独立于上游守卫，因此符号稳定存在。

#include "MNN/expr/Expr.hpp"  // 定义 MNN::Express::VARP —— qwenVideoProcess 的参数类型

#include <vector>

namespace MNN {
namespace Express {
    // VARP = std::shared_ptr<Variable>，已在 Expr.hpp 中声明，此处仅占位以明确命名空间。
}
namespace Transformer {

// 仅声明与上游完全一致的成员函数签名；类的具体布局无关紧要，
// 链接器按 mangled name 归并到 omni.cpp.o 中引用的同一符号。
class Omni {
public:
    std::vector<int> qwenVideoProcess(const std::vector<MNN::Express::VARP>& frames,
                                      const std::vector<float>& timestamps);
};

std::vector<int> Omni::qwenVideoProcess(const std::vector<MNN::Express::VARP>& frames,
                                        const std::vector<float>& timestamps) {
    (void)frames;
    (void)timestamps;
    return std::vector<int>(0);
}

}  // namespace Transformer
}  // namespace MNN
