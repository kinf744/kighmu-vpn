import sys

# hev-config.h
h = open('hev-socks5-tunnel/src/hev-config.h').read()
h = h.replace(
    'HevConfigServer *hev_config_get_socks5_server (void);',
    '#define HEV_MAX_SOCKS_SERVERS 8\nHevConfigServer *hev_config_get_socks5_server (void);\nint hev_config_get_socks5_server_count (void);'
)
open('hev-socks5-tunnel/src/hev-config.h', 'w').write(h)

# hev-config.c
c = open('hev-socks5-tunnel/src/hev-config.c').read()
c = c.replace('#include "hev-config.h"', '#include <stdatomic.h>\n#include "hev-config.h"')
c = c.replace('static HevConfigServer srv;', 'static HevConfigServer srv[HEV_MAX_SOCKS_SERVERS];\nstatic int srv_count = 0;\nstatic atomic_int srv_rr = 0;')
c = c.replace('    strncpy (srv.addr,', '    strncpy (srv[srv_count].addr,')
c = c.replace('    srv.port =', '    srv[srv_count].port =')
c = c.replace('    srv.pipeline =', '    srv[srv_count].pipeline =')
c = c.replace('    srv.udp_in_udp =', '    srv[srv_count].udp_in_udp =')
c = c.replace('    strncpy (srv.udp_addr,', '    strncpy (srv[srv_count].udp_addr,')
c = c.replace('        srv.user =', '        srv[srv_count].user =')
c = c.replace('        srv.pass =', '        srv[srv_count].pass =')
c = c.replace('    srv.mark =', '    srv[srv_count].mark =')
c = c.replace(
    '    if (mark)\n        srv.mark = strtoul (mark, NULL, 0);',
    '    if (mark)\n        srv[srv_count].mark = strtoul (mark, NULL, 0);\n    srv_count++;'
)
c = c.replace(
    'static char _user[256];\nstatic char _pass[256];',
    'static char _user[HEV_MAX_SOCKS_SERVERS][256];\nstatic char _pass[HEV_MAX_SOCKS_SERVERS][256];'
)
c = c.replace(
    '        strncpy (_user, user, 256 - 1);\n        strncpy (_pass, pass, 256 - 1);\n        srv.user = _user;\n        srv.pass = _pass;',
    '        strncpy (_user[srv_count], user, 256 - 1);\n        strncpy (_pass[srv_count], pass, 256 - 1);\n        srv[srv_count].user = _user[srv_count];\n        srv[srv_count].pass = _pass[srv_count];'
)
c = c.replace(
    'hev_config_get_socks5_server (void)\n{\n    return &srv;\n}',
    'hev_config_get_socks5_server (void)\n{\n    if (srv_count <= 1) return &srv[0];\n    int idx = atomic_fetch_add (&srv_rr, 1) % srv_count;\n    return &srv[idx];\n}\n\nint\nhev_config_get_socks5_server_count (void)\n{\n    return srv_count;\n}'
)
open('hev-socks5-tunnel/src/hev-config.c', 'w').write(c)
print("Patch OK! srv_count:", c.count("srv_count"))
