#include "rtc_base/checks.h"

#include <stdlib.h>

void rtc_FatalMessage(const char* file, int line, const char* msg) {
  (void)file;
  (void)line;
  (void)msg;
  abort();
}
