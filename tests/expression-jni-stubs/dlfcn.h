/* Host-only POSIX loader boundary. Production builds use the NDK header. */
#ifndef EXPRESSION_TEST_DLFCN_H
#define EXPRESSION_TEST_DLFCN_H
#define RTLD_NOW 2
#define RTLD_LOCAL 0
void *dlopen(const char *,int);
void *dlsym(void *,const char *);
int dlclose(void *);
#endif
