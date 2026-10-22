// WebView2.h includes this Windows SDK header, which the MinGW headers shipped with zig lack. It declares one type,
// the token that removes an event handler again.
#pragma once

typedef struct EventRegistrationToken {
    __int64 value;
} EventRegistrationToken;
