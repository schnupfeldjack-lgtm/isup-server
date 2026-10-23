#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
static void hang_on_exit(void) {
    const char *marker = getenv("RECOVERY_EXIT_HANDLER_MARKER");
    if (marker) {
        FILE *file = fopen(marker, "w");
        if (file) { fputs("native atexit entered\n", file); fclose(file); }
    }
    for (;;) pause();
}
void install_hanging_handler(void) { atexit(hang_on_exit); }
