/* The variadic exec functions for libpuppy_remap.so (see remap.zig, which
 * cannot define C variadics on AArch64 with Zig 0.16). They collect their
 * arguments and call the remapping execve/execvpe. */
#include <stdarg.h>

#define MAX_ARGS 4096

extern char **environ;
int execve(const char *path, char *const argv[], char *const envp[]);
int execvpe(const char *file, char *const argv[], char *const envp[]);

static void collect(const char *arg0, va_list ap, char **out) {
    int i = 0;
    out[i++] = (char *)arg0;
    if (arg0)
        while (i < MAX_ARGS - 1 && (out[i++] = va_arg(ap, char *)) != 0) {}
    out[i] = 0;
}

int execl(const char *path, const char *arg0, ...) {
    char *argv[MAX_ARGS];
    va_list ap;
    va_start(ap, arg0);
    collect(arg0, ap, argv);
    va_end(ap);
    return execve(path, argv, environ);
}

int execlp(const char *file, const char *arg0, ...) {
    char *argv[MAX_ARGS];
    va_list ap;
    va_start(ap, arg0);
    collect(arg0, ap, argv);
    va_end(ap);
    return execvpe(file, argv, environ);
}

int execle(const char *path, const char *arg0, ...) {
    char *argv[MAX_ARGS];
    va_list ap;
    va_start(ap, arg0);
    collect(arg0, ap, argv);
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    return execve(path, argv, envp);
}
