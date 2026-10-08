/* Test-only shim: lets CUPS's ippeveprinter start in a container without IPv6 by giving its
   IPv6 listener a dummy socket that never receives connections. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
static int fake[64]; static int nfake;
static int isfake(int fd) { for (int i = 0; i < nfake; i++) if (fake[i] == fd) return 1; return 0; }
int socket(int domain, int type, int protocol) {
    static int (*real)(int, int, int); if (!real) real = dlsym(RTLD_NEXT, "socket");
    if (domain == AF_INET6) { int fd = real(AF_UNIX, SOCK_STREAM, 0); if (fd >= 0 && nfake < 64) fake[nfake++] = fd; return fd; }
    return real(domain, type, protocol);
}
int setsockopt(int fd, int level, int name, const void *v, socklen_t l) {
    static int (*real)(int, int, int, const void *, socklen_t); if (!real) real = dlsym(RTLD_NEXT, "setsockopt");
    if (isfake(fd)) return 0; return real(fd, level, name, v, l);
}
int bind(int fd, const struct sockaddr *a, socklen_t l) {
    static int (*real)(int, const struct sockaddr *, socklen_t); if (!real) real = dlsym(RTLD_NEXT, "bind");
    if (isfake(fd)) return 0; return real(fd, a, l);
}
int listen(int fd, int n) {
    static int (*real)(int, int); if (!real) real = dlsym(RTLD_NEXT, "listen");
    if (isfake(fd)) return 0; return real(fd, n);
}
