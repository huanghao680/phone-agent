/*
 * landlock-wrap — unprivileged filesystem confinement via the Landlock LSM.
 *
 * Used by the phone-agent bwrap shim (assets/scripts/bwrap) as the preferred
 * sandbox tier on kernels >= 5.13; the shim falls back to proot when the
 * probe fails (older kernels, or an SELinux policy that blocks the syscalls).
 *
 * Usage:
 *   landlock-wrap --probe
 *       exit 0 when a Landlock ruleset can be created on this kernel,
 *       exit 3 otherwise (ENOSYS / EOPNOTSUPP / EACCES / ...).
 *
 *   landlock-wrap [--ro PATH]... [--rw PATH]... -- CMD [ARG]...
 *       Grants execute/read on every --ro tree and full file access on every
 *       --rw path (tree or single file), revokes every other handled file
 *       effect, then executes CMD. File effects only — sockets, signals and
 *       process visibility are untouched, matching dsh's own runners' scope.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>

#include <linux/types.h>
#if __has_include(<linux/landlock.h>)
#include <linux/landlock.h>
#else
/* Older sysroots: define the ABI ourselves (kernel 5.13 shapes, stable). */
#define LANDLOCK_ACCESS_FS_EXECUTE			(1ULL << 0)
#define LANDLOCK_ACCESS_FS_WRITE_FILE			(1ULL << 1)
#define LANDLOCK_ACCESS_FS_READ_FILE			(1ULL << 2)
#define LANDLOCK_ACCESS_FS_READ_DIR			(1ULL << 3)
#define LANDLOCK_ACCESS_FS_REMOVE_DIR			(1ULL << 4)
#define LANDLOCK_ACCESS_FS_REMOVE_FILE			(1ULL << 5)
#define LANDLOCK_ACCESS_FS_MAKE_CHAR			(1ULL << 6)
#define LANDLOCK_ACCESS_FS_MAKE_DIR			(1ULL << 7)
#define LANDLOCK_ACCESS_FS_MAKE_REG			(1ULL << 8)
#define LANDLOCK_ACCESS_FS_MAKE_SOCK			(1ULL << 9)
#define LANDLOCK_ACCESS_FS_MAKE_FIFO			(1ULL << 10)
#define LANDLOCK_ACCESS_FS_REFER			(1ULL << 11) /* ABI 2 */
#define LANDLOCK_ACCESS_FS_TRUNCATE			(1ULL << 12) /* ABI 2 */
#define LANDLOCK_RULE_PATH_BENEATH			1
#define LANDLOCK_CREATE_RULESET_VERSION			(1U << 0)
struct landlock_ruleset_attr {
	__u64 handled_access_fs;
};
struct landlock_path_beneath_attr {
	__u64 allowed_access;
	__s32 parent_fd;
} __attribute__((packed));
#endif

/* aarch64 syscall numbers match the generic table */
#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset	444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule		445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self	446
#endif

#define RO_RIGHTS	(LANDLOCK_ACCESS_FS_EXECUTE | \
			 LANDLOCK_ACCESS_FS_READ_FILE | \
			 LANDLOCK_ACCESS_FS_READ_DIR)
/* everything a confined shell may do to a writable root */
#define RW_RIGHTS	(RO_RIGHTS | LANDLOCK_ACCESS_FS_WRITE_FILE | \
			 LANDLOCK_ACCESS_FS_REMOVE_DIR | \
			 LANDLOCK_ACCESS_FS_REMOVE_FILE | \
			 LANDLOCK_ACCESS_FS_MAKE_CHAR | \
			 LANDLOCK_ACCESS_FS_MAKE_DIR | \
			 LANDLOCK_ACCESS_FS_MAKE_REG | \
			 LANDLOCK_ACCESS_FS_MAKE_SOCK | \
			 LANDLOCK_ACCESS_FS_MAKE_FIFO)

static int abi_version(void)
{
	long v = syscall(__NR_landlock_create_ruleset, NULL, 0,
			 LANDLOCK_CREATE_RULESET_VERSION);
	return (int)v; /* negative errno on failure */
}

static int add_path_rule(int ruleset_fd, const char *path, __u64 rights)
{
	struct landlock_path_beneath_attr attr;
	int fd = open(path, O_PATH | O_CLOEXEC);
	if (fd < 0) {
		fprintf(stderr, "landlock-wrap: open %s: %s\n", path,
			strerror(errno));
		return -1;
	}
	attr.allowed_access = rights;
	attr.parent_fd = fd;
	if (syscall(__NR_landlock_add_rule, ruleset_fd,
		    LANDLOCK_RULE_PATH_BENEATH, &attr, 0) != 0) {
		fprintf(stderr, "landlock-wrap: add_rule %s: %s\n", path,
			strerror(errno));
		close(fd);
		return -1;
	}
	close(fd);
	return 0;
}

int main(int argc, char **argv)
{
	int abi = abi_version();
	if (argc == 2 && strcmp(argv[1], "--probe") == 0)
		return abi >= 1 ? 0 : 3;
	if (abi < 1) {
		fprintf(stderr, "landlock-wrap: kernel lacks Landlock (abi %d)\n",
			abi);
		return 3;
	}

	__u64 handled = RO_RIGHTS | LANDLOCK_ACCESS_FS_WRITE_FILE |
			LANDLOCK_ACCESS_FS_REMOVE_DIR |
			LANDLOCK_ACCESS_FS_REMOVE_FILE |
			LANDLOCK_ACCESS_FS_MAKE_CHAR |
			LANDLOCK_ACCESS_FS_MAKE_DIR |
			LANDLOCK_ACCESS_FS_MAKE_REG |
			LANDLOCK_ACCESS_FS_MAKE_SOCK |
			LANDLOCK_ACCESS_FS_MAKE_FIFO;
	if (abi >= 2)
		handled |= LANDLOCK_ACCESS_FS_REFER | LANDLOCK_ACCESS_FS_TRUNCATE;

	struct landlock_ruleset_attr ruleset_attr = { .handled_access_fs = handled };
	int ruleset_fd = (int)syscall(__NR_landlock_create_ruleset, &ruleset_attr,
				      sizeof(ruleset_attr), 0);
	if (ruleset_fd < 0) {
		fprintf(stderr, "landlock-wrap: create_ruleset: %s\n",
			strerror(errno));
		return 1;
	}

	int i = 1;
	int saw_sep = 0;
	for (; i < argc; i++) {
		const char *a = argv[i];
		if (strcmp(a, "--") == 0) { i++; saw_sep = 1; break; }
		if (strcmp(a, "--ro") == 0 && i + 1 < argc) {
			if (add_path_rule(ruleset_fd, argv[++i], RO_RIGHTS) != 0)
				return 1;
		} else if (strcmp(a, "--rw") == 0 && i + 1 < argc) {
			/* refer/truncate only on ABIs that handle them */
			__u64 rights = RW_RIGHTS | (abi >= 2 ?
				(LANDLOCK_ACCESS_FS_REFER |
				 LANDLOCK_ACCESS_FS_TRUNCATE) : 0);
			if (add_path_rule(ruleset_fd, argv[++i], rights) != 0)
				return 1;
		} else {
			fprintf(stderr,
				"landlock-wrap: bad argument %s\n", a);
			return 2;
		}
	}
	if (!saw_sep || i >= argc) {
		fprintf(stderr, "landlock-wrap: no command after --\n");
		return 2;
	}

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
		fprintf(stderr, "landlock-wrap: no_new_privs: %s\n",
			strerror(errno));
		return 1;
	}
	if (syscall(__NR_landlock_restrict_self, ruleset_fd, 0) != 0) {
		fprintf(stderr, "landlock-wrap: restrict_self: %s\n",
			strerror(errno));
		return 1;
	}
	execvp(argv[i], &argv[i]);
	fprintf(stderr, "landlock-wrap: exec %s: %s\n", argv[i],
		strerror(errno));
	return 127;
}
