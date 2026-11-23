/* HCCR 预处理 C 实现头文件：Python(ctypes) 与 Android(JNI) 共用 */
#ifndef HCCR_PREPROCESS_H
#define HCCR_PREPROCESS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HCCR_CANVAS_SIZE 64
#define HCCR_CONTENT_SIZE 56
#define HCCR_FG_THRESHOLD 220

/*
 * 把 (w x h) 灰度图预处理为模型输入 float[64*64]。
 * gray: row-major 灰度数据，长度 w*h（255=白底，0=黑笔画）
 * out:  调用方保证至少 64*64=4096 元素
 * return: 1=成功；0=全白（out 已置零）
 */
int hccr_preprocess(const uint8_t* gray, int w, int h, float* out);

#ifdef __cplusplus
}
#endif
#endif /* HCCR_PREPROCESS_H */
