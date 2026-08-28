/*
 * qurohost — Zorv AI native terminal / CMS / developer-environment host.
 *
 * Runs as a standalone executable packed as libqurohost.so (same mechanism as
 * libproot.so: Android grants +x to .so in nativeLibraryDir, launched via
 * ProcessBuilder). In Linux mode Kotlin launches it *inside* proot, so its
 * child shell is the Ubuntu /bin/sh; on device it falls back to /system/bin/sh.
 *
 * Wire protocol (line oriented, '\n' delimited):
 *   Kotlin -> qurohost (STDIN):
 *     - any line NOT starting with US(0x1f)"@qurohost " is forwarded verbatim
 *       to the child shell (Kotlin already appends the completion sentinel).
 *     - a line starting with US"@qurohost " is a CONTROL command, handled in C,
 *       never forwarded to the shell.
 *   qurohost -> Kotlin (STDOUT):
 *     - terminal output lines from the child shell (including the sentinel).
 *     - CONTROL response: US"@qurohost-resp " + JSON + '\n'.
 *
 * CMS layout: $QURO_CMS_ROOT (default /root/cms) / <id> / entry.sh (+ .ready).
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/wait.h>
#include <dirent.h>
#include <sys/stat.h>
#include <ctype.h>

#define CONTROL_PREFIX "\x1f@qurohost "
#define CONTROL_RESP_PREFIX "\x1f@qurohost-resp "
#define CONTROL_PREFIX_LEN 10   /* strlen of CONTROL_PREFIX (includes the US byte) */
#define CONTROL_RESP_PREFIX_LEN 15

static const char *g_cms_root = "/root/cms";
static pthread_mutex_t g_out_lock = PTHREAD_MUTEX_INITIALIZER;

/* child shell pipes */
static int g_shell_in = -1;   /* write end -> shell stdin */
static int g_shell_out = -1;  /* read end  <- shell stdout/stderr */
static pid_t g_shell_pid = -1;

/* ---- safe stdout write (line) ---- */
static void out_line(const char *s) {
    pthread_mutex_lock(&g_out_lock);
    fputs(s, stdout);
    fputc('\n', stdout);
    fflush(stdout);
    pthread_mutex_unlock(&g_out_lock);
}

/* ---- base64 decode (standard, no padding required) ---- */
static int b64_val(int c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* decode src (base64) into out (caller-allocated, >= 3*len/4+1). returns bytes written. */
static long b64_decode(const char *src, size_t slen, unsigned char *out, size_t outcap) {
    long o = 0;
    int acc = 0, bits = 0;
    for (size_t i = 0; i < slen; i++) {
        int v = b64_val(src[i]);
        if (v < 0) continue; /* skip whitespace / '=' */
        acc = (acc << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if ((size_t)o + 1 <= outcap) out[o++] = (unsigned char)((acc >> bits) & 0xFF);
        }
    }
    return o;
}

/* ---- tiny json string escape ---- */
static void json_escape(const char *s, char *buf, size_t cap) {
    size_t j = 0;
    if (s == NULL) s = "";
    for (size_t i = 0; s[i] && j + 2 < cap; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"' || c == '\\') {
            buf[j++] = '\\'; buf[j++] = (char)c;
        } else if (c == '\n') {
            buf[j++] = '\\'; buf[j++] = 'n';
        } else if (c == '\r') {
            buf[j++] = '\\'; buf[j++] = 'r';
        } else if (c < 0x20) {
            j += (size_t)snprintf(buf + j, cap - j, "\\u%04x", c);
        } else {
            buf[j++] = (char)c;
        }
    }
    buf[j] = '\0';
}

/* ---- run a host command, capture combined output (for dev-env probing) ---- */
static int run_capture(const char *cmd, char *out, size_t outcap) {
    out[0] = '\0';
    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    if (pid == 0) {
        dup2(pipefd[1], 1);
        dup2(pipefd[1], 2);
        close(pipefd[0]); close(pipefd[1]);
        execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);
    size_t total = 0;
    char tmp[512];
    ssize_t n;
    while ((n = read(pipefd[0], tmp, sizeof(tmp) - 1)) > 0) {
        if (total + (size_t)n >= outcap) n = (ssize_t)(outcap - total - 1);
        memcpy(out + total, tmp, (size_t)n);
        total += (size_t)n;
        if (total + 1 >= outcap) break;
    }
    out[total] = '\0';
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* ---- path exists / is executable ---- */
static int file_is_exec(const char *p) {
    struct stat st;
    if (stat(p, &st) != 0) return 0;
    return (st.st_mode & S_IXUSR) != 0;
}

/* ---- which: locate binary in PATH (returns 1 if found, copies resolved or name) ---- */
static int which_bin(const char *name, char *resolved, size_t cap) {
    const char *path = getenv("PATH");
    if (path == NULL) path = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    char dup[1024];
    snprintf(dup, sizeof(dup), "%s", path);
    char *save = NULL;
    for (char *tok = strtok_r(dup, ":", &save); tok; tok = strtok_r(NULL, ":", &save)) {
        char cand[1100];
        snprintf(cand, sizeof(cand), "%s/%s", tok, name);
        if (file_is_exec(cand)) {
            snprintf(resolved, cap, "%s", cand);
            return 1;
        }
    }
    return 0;
}

/* ================= CMS handlers ================= */

static void cms_status_or_list(int list_mode) {
    /* list every subdir under cms_root; report ready + entry presence */
    char buf[8300];
    snprintf(buf, sizeof(buf),
        "%s{\"ok\":true,\"action\":\"%s\",\"root\":\"%s\",\"modules\":[",
        CONTROL_RESP_PREFIX,
        list_mode ? "list" : "status", g_cms_root);
    /* Build the JSON in a buffer then emit once. */
    size_t len = strlen(buf);
    DIR *d = opendir(g_cms_root);
    if (d == NULL) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"%s\",\"error\":\"opendir %s: %s\"}",
                 CONTROL_RESP_PREFIX, list_mode ? "list" : "status", g_cms_root, strerror(errno));
        out_line(r);
        return;
    }
    int first = 1;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        char mdir[1200];
        snprintf(mdir, sizeof(mdir), "%s/%s", g_cms_root, de->d_name);
        struct stat st;
        if (stat(mdir, &st) != 0 || !S_ISDIR(st.st_mode)) continue;
        char entry[1300], ready[1300];
        snprintf(entry, sizeof(entry), "%s/entry.sh", mdir);
        snprintf(ready, sizeof(ready), "%s/.ready", mdir);
        int has_entry = file_is_exec(entry);
        int has_ready = (access(ready, F_OK) == 0);
        char mjson[1024];
        snprintf(mjson, sizeof(mjson),
            "%s{\"id\":\"%s\",\"entry\":%s,\"ready\":%s}",
            first ? "" : ",", de->d_name, has_entry ? "true" : "false", has_ready ? "true" : "false");
        if (len + strlen(mjson) + 2 < sizeof(buf)) {
            strcat(buf, mjson);
            len += strlen(mjson);
            first = 0;
        }
    }
    closedir(d);
    if (len + 4 < sizeof(buf)) { strcat(buf, "]}"); }
    out_line(buf);
}

static void cms_deploy(const char *id, const char *b64) {
    if (id == NULL || id[0] == '\0' || strchr(id, '/') != NULL || strcmp(id, "..") == 0) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"deploy\",\"error\":\"invalid id\"}", CONTROL_RESP_PREFIX);
        out_line(r);
        return;
    }
    char mdir[1200];
    snprintf(mdir, sizeof(mdir), "%s/%s", g_cms_root, id);
    mkdir(g_cms_root, 0755);
    if (mkdir(mdir, 0755) != 0 && errno != EEXIST) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"deploy\",\"error\":\"mkdir %s: %s\"}",
                 CONTROL_RESP_PREFIX, mdir, strerror(errno));
        out_line(r);
        return;
    }
    char entry[1300];
    snprintf(entry, sizeof(entry), "%s/entry.sh", mdir);
    FILE *f = fopen(entry, "wb");
    if (f == NULL) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"deploy\",\"error\":\"fopen %s: %s\"}",
                 CONTROL_RESP_PREFIX, entry, strerror(errno));
        out_line(r);
        return;
    }
    size_t blen = b64 ? strlen(b64) : 0;
    unsigned char *dec = malloc(blen / 3 + 2 + 1);
    long n = dec ? b64_decode(b64, blen, dec, blen / 3 + 2 + 1) : 0;
    fwrite(dec, 1, (size_t)n, f);
    free(dec);
    fclose(f);
    chmod(entry, 0755);
    char ready[1300];
    snprintf(ready, sizeof(ready), "%s/.ready", mdir);
    fclose(fopen(ready, "w"));
    char r[256];
    snprintf(r, sizeof(r), "%s{\"ok\":true,\"action\":\"deploy\",\"id\":\"%s\",\"path\":\"%s\"}",
             CONTROL_RESP_PREFIX, id, entry);
    out_line(r);
}

static void cms_run(const char *id) {
    if (id == NULL || id[0] == '\0' || strchr(id, '/') != NULL) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"run\",\"error\":\"invalid id\"}", CONTROL_RESP_PREFIX);
        out_line(r);
        return;
    }
    char mdir[1200], entry[1300], log[1300];
    snprintf(mdir, sizeof(mdir), "%s/%s", g_cms_root, id);
    snprintf(entry, sizeof(entry), "%s/entry.sh", mdir);
    snprintf(log, sizeof(log), "%s/run.log", mdir);
    if (!file_is_exec(entry)) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"run\",\"error\":\"entry.sh missing/not executable\"}", CONTROL_RESP_PREFIX);
        out_line(r);
        return;
    }
    /* spawn: nohup sh entry.sh > run.log 2>&1 &  (persistent, detached) */
    char cmd[1600];
    snprintf(cmd, sizeof(cmd),
        "cd '%s' && nohup /bin/sh ./entry.sh > '%s' 2>&1 & echo $!", mdir, log);
    char out[256];
    int code = run_capture(cmd, out, sizeof(out));
    char r[320];
    snprintf(r, sizeof(r), "%s{\"ok\":true,\"action\":\"run\",\"id\":\"%s\",\"pid\":\"%s\",\"code\":%d}",
             CONTROL_RESP_PREFIX, id, out, code);
    out_line(r);
}

static void cms_remove(const char *id) {
    if (id == NULL || id[0] == '\0' || strchr(id, '/') != NULL) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"remove\",\"error\":\"invalid id\"}", CONTROL_RESP_PREFIX);
        out_line(r);
        return;
    }
    char mdir[1200];
    snprintf(mdir, sizeof(mdir), "%s/%s", g_cms_root, id);
    char cmd[1400];
    snprintf(cmd, sizeof(cmd), "rm -rf '%s'", mdir);
    char out[256];
    int code = run_capture(cmd, out, sizeof(out));
    char r[256];
    snprintf(r, sizeof(r), "%s{\"ok\":true,\"action\":\"remove\",\"id\":\"%s\",\"code\":%d}",
             CONTROL_RESP_PREFIX, id, code);
    out_line(r);
}

/* ================= DevEnv handlers ================= */

static void devenv_status(void) {
    const char *tools[] = {"java", "javac", "gradle", "rustc", "cargo", "go", "node", "npm", "python3", "ssh", NULL};
    char buf[2048];
    snprintf(buf, sizeof(buf), "{\"ok\":true,\"action\":\"status\",\"tools\":{");
    size_t len = strlen(buf);
    int first = 1;
    for (int i = 0; tools[i]; i++) {
        char resolved[1100];
        int found = which_bin(tools[i], resolved, sizeof(resolved));
        char one[256];
        if (found) {
            snprintf(one, sizeof(one), "%s\"%s\":{\"present\":true,\"path\":\"%s\"}",
                     first ? "" : ",", tools[i], resolved);
        } else {
            snprintf(one, sizeof(one), "%s\"%s\":{\"present\":false}", first ? "" : ",", tools[i]);
        }
        if (len + strlen(one) + 2 < sizeof(buf)) { strcat(buf, one); len += strlen(one); first = 0; }
    }
    if (len + 4 < sizeof(buf)) strcat(buf, "}}");
    out_line(buf);
}

static void devenv_provision(const char *tool) {
    if (tool == NULL || tool[0] == '\0' || strchr(tool, '/') != NULL) {
        char r[256];
        snprintf(r, sizeof(r), "%s{\"ok\":false,\"action\":\"provision\",\"error\":\"invalid tool\"}", CONTROL_RESP_PREFIX);
        out_line(r);
        return;
    }
    /* Prefer an existing provision script under the engine tree; otherwise report unsupported. */
    char script[1400];
    snprintf(script, sizeof(script), "%s/_engine/provision/%s.sh", g_cms_root, tool);
    if (!file_is_exec(script)) {
        char r[320];
        snprintf(r, sizeof(r),
            "%s{\"ok\":false,\"action\":\"provision\",\"tool\":\"%s\",\"error\":\"no native provision script; use Kotlin CmsEnvProvisioner\"}",
            CONTROL_RESP_PREFIX, tool);
        out_line(r);
        return;
    }
    char cmd[1600];
    snprintf(cmd, sizeof(cmd), "sh '%s'", script);
    char out[4096];
    int code = run_capture(cmd, out, sizeof(out));
    /* trim trailing newline */
    size_t ol = strlen(out);
    while (ol > 0 && (out[ol-1] == '\n' || out[ol-1] == '\r')) out[--ol] = '\0';
    char esc[4200];
    json_escape(out, esc, sizeof(esc));
    char r[4400];
    snprintf(r, sizeof(r), "%s{\"ok\":true,\"action\":\"provision\",\"tool\":\"%s\",\"code\":%d,\"output\":\"%s\"}",
             CONTROL_RESP_PREFIX, tool, code, esc);
    out_line(r);
}

/* ================= Control dispatch ================= */

static void handle_control(const char *cmd) {
    /* cmd points just after CONTROL_PREFIX */
    if (strncmp(cmd, "cms list", 8) == 0) { cms_status_or_list(1); return; }
    if (strncmp(cmd, "cms status", 10) == 0) { cms_status_or_list(0); return; }
    if (strncmp(cmd, "cms deploy ", 11) == 0) {
        /* cms deploy <id> <base64> */
        const char *p = cmd + 11;
        while (*p == ' ') p++;
        const char *id = p;
        while (*p && *p != ' ') p++;
        size_t idlen = (size_t)(p - id);
        char idbuf[256];
        if (idlen >= sizeof(idbuf)) idlen = sizeof(idbuf) - 1;
        memcpy(idbuf, id, idlen); idbuf[idlen] = '\0';
        while (*p == ' ') p++;
        cms_deploy(idbuf, p);
        return;
    }
    if (strncmp(cmd, "cms run ", 8) == 0) { cms_run(cmd + 8); return; }
    if (strncmp(cmd, "cms remove ", 11) == 0) { cms_remove(cmd + 11); return; }
    if (strncmp(cmd, "devenv status", 13) == 0) { devenv_status(); return; }
    if (strncmp(cmd, "devenv provision ", 16) == 0) { devenv_provision(cmd + 16); return; }

    char r[256];
    snprintf(r, sizeof(r), "%s{\"ok\":false,\"error\":\"unknown control: %s\"}", CONTROL_RESP_PREFIX, cmd);
    out_line(r);
}

/* ================= STDIN pump thread ================= */

static void *stdin_pump(void *arg) {
    (void)arg;
    char *line = NULL;
    size_t cap = 0;
    ssize_t n;
    /* read line by line from STDIN */
    while ((n = getline(&line, &cap, stdin)) != -1) {
        /* strip trailing newline */
        while (n > 0 && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = '\0';
        if (n >= CONTROL_PREFIX_LEN && memcmp(line, CONTROL_PREFIX, CONTROL_PREFIX_LEN) == 0) {
            handle_control(line + CONTROL_PREFIX_LEN);
            continue;
        }
        /* forward to child shell (no lock needed: writing to the shell pipe, not stdout) */
        if (g_shell_in >= 0) {
            if (write(g_shell_in, line, (size_t)n) >= 0) {
                char nl = '\n';
                write(g_shell_in, &nl, 1);
            }
        }
    }
    /* stdin EOF: close shell stdin so the shell exits */
    if (g_shell_in >= 0) { close(g_shell_in); g_shell_in = -1; }
    free(line);
    return NULL;
}

int main(int argc, char **argv) {
    const char *shell = (argc > 1) ? argv[1] : "/system/bin/sh";
    const char *cms = getenv("QURO_CMS_ROOT");
    if (cms && cms[0]) g_cms_root = cms;

    /* banner on stdout so Kotlin can confirm the native host is up */
    out_line("\x1f@qurohost-resp {\"ok\":true,\"action\":\"hello\",\"shell\":\"qurohost\",\"cms_root\":\"/root/cms\"}");

    int inp[2], outp[2];
    if (pipe(inp) != 0 || pipe(outp) != 0) {
        out_line("\x1f@qurohost-resp {\"ok\":false,\"action\":\"hello\",\"error\":\"pipe failed\"}");
        return 1;
    }
    pid_t pid = fork();
    if (pid < 0) {
        out_line("\x1f@qurohost-resp {\"ok\":false,\"action\":\"hello\",\"error\":\"fork failed\"}");
        return 1;
    }
    if (pid == 0) {
        /* child: dup pipes to stdin/stdout/stderr, exec shell */
        dup2(inp[0], 0);
        dup2(outp[1], 1);
        dup2(outp[1], 2);
        close(inp[0]); close(inp[1]); close(outp[0]); close(outp[1]);
        execl(shell, shell, (char *)NULL);
        /* if exec fails, try device shell */
        execl("/system/bin/sh", "sh", (char *)NULL);
        _exit(127);
    }
    /* parent */
    close(inp[0]); close(outp[1]);
    g_shell_in = inp[1];
    g_shell_out = outp[0];
    g_shell_pid = pid;

    /* stdin pump thread */
    pthread_t tid;
    pthread_create(&tid, NULL, stdin_pump, NULL);

    /* main: forward shell output -> stdout */
    char buf[4096];
    ssize_t rn;
    while ((rn = read(g_shell_out, buf, sizeof(buf) - 1)) > 0) {
        buf[rn] = '\0';
        /* forward raw (may contain multiple lines + sentinel); write as-is */
        pthread_mutex_lock(&g_out_lock);
        fwrite(buf, 1, (size_t)rn, stdout);
        fflush(stdout);
        pthread_mutex_unlock(&g_out_lock);
    }
    close(g_shell_out);

    /* shell exited: unblock the stdin pump (getline hits EOF) then join.
       NOTE: Android bionic does NOT implement pthread_cancel, so we close STDIN_FILENO
       instead of cancelling — the pump thread returns cleanly and can be joined safely. */
    close(STDIN_FILENO);
    pthread_join(tid, NULL);
    int status = 0;
    waitpid(pid, &status, 0);
    out_line("\x1f@qurohost-resp {\"ok\":true,\"action\":\"bye\"}");
    return 0;
}
