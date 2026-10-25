// NDK 29 bionic headers use clang nullability keywords in array
// parameter position and an overloadable ioctl; zig 0.16's translate-c
// (clang 21) rejects both. Neutralize for translation.
#define _Nullable
#define _Nonnull
#define _Null_unspecified
#define BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD

#include <jni.h>
#include <libssh2.h>
#include <libssh2_sftp.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <semaphore.h>
#include <sys/socket.h>
#include <time.h>
