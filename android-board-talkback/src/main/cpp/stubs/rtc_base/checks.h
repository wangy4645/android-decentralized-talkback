#ifndef RTC_BASE_CHECKS_H_
#define RTC_BASE_CHECKS_H_

#include <stdlib.h>

#ifndef RTC_NORETURN
#define RTC_NORETURN __attribute__((noreturn))
#endif

RTC_NORETURN void rtc_FatalMessage(const char* file, int line, const char* msg);

#define RTC_CHECK(condition) \
  do {                       \
    if (!(condition)) {      \
      abort();               \
    }                        \
  } while (0)

#define RTC_DCHECK(condition) RTC_CHECK(condition)
#define RTC_DCHECK_EQ(a, b) RTC_CHECK((a) == (b))
#define RTC_DCHECK_NE(a, b) RTC_CHECK((a) != (b))
#define RTC_DCHECK_LT(a, b) RTC_CHECK((a) < (b))
#define RTC_DCHECK_LE(a, b) RTC_CHECK((a) <= (b))
#define RTC_DCHECK_GT(a, b) RTC_CHECK((a) > (b))
#define RTC_DCHECK_GE(a, b) RTC_CHECK((a) >= (b))

#endif  // RTC_BASE_CHECKS_H_
