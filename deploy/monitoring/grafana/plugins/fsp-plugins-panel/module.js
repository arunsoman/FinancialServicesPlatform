/*
 * FSP plugins panel — the ops console's control surface for plugins (docs/plugin-registration.md).
 *
 * FAPI 2.0: this panel holds NO credential. Grafana is served at https://<env host>/ops/ behind
 * nginx auth_request → BFF (/bff/ops/auth); every action below is a same-origin call to the BFF
 * (/api/**, session cookie + CSRF header), forwarded with the person's DPoP-bound token through the
 * gateway to core — the sole PDP, which enforces plugin.register / plugin.manage and four-eyes at
 * person_id (maker ≠ checker). The panel only shows what core allows (canDecide / blockedReason).
 *
 * Data: the panel's query is `fsp_plugin_drift` (format table, instant) — what this environment
 * ships, its state and the exact manifest file; plugin statuses + approvals come from core.
 * Plain AMD + React.createElement: no build step, nothing downloaded.
 */
define(['react', '@grafana/data'], function (React, grafanaData) {
  'use strict';
  var h = React.createElement;
  var useState = React.useState, useEffect = React.useEffect, useCallback = React.useCallback;

  var REGISTERABLE = { not_registered: 1, behind: 1, ahead: 1, key_mismatch: 1, content_mismatch: 1 };
  var OPS = ['PLUGIN_REGISTER', 'PLUGIN_SUSPEND', 'PLUGIN_RESUME'];

  // ---------------------------------------------------------------- BFF calls (same origin)
  var csrfCache = null;
  function csrf() {
    if (csrfCache) return Promise.resolve(csrfCache);
    return fetch('/bff/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { if (!r.ok) throw new Error('CSRF token unavailable (' + r.status + ')'); return r.json(); })
      .then(function (c) { csrfCache = c; return c; });
  }
  function call(method, path, body) {
    var mutating = method !== 'GET';
    return (mutating ? csrf() : Promise.resolve(null)).then(function (c) {
      var headers = { Accept: 'application/json' };
      if (mutating) { headers['Content-Type'] = 'application/json'; headers[c.headerName] = c.token; }
      return fetch(path, { method: method, credentials: 'same-origin', headers: headers,
                           body: body === undefined ? undefined : JSON.stringify(body) });
    }).then(function (r) {
      return r.text().then(function (t) {
        var j = null; try { j = t ? JSON.parse(t) : null; } catch (e) { j = null; }
        if (r.status === 403 && j && j.code === 'CSRF') csrfCache = null;
        if (!r.ok) {
          var msg = (j && (j.detail || j.title || j.code)) || ('HTTP ' + r.status);
          if (j && j.code) msg = j.code + ': ' + msg;
          if (r.status === 401) msg = 'Your FSP session ended — reload the page to sign in again.';
          throw new Error(msg);
        }
        return j;
      });
    });
  }
  function sha256Hex(text) {
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    });
  }

  // ---------------------------------------------------------------- query rows
  function valueAt(values, i) {
    if (!values) return undefined;
    return typeof values.get === 'function' ? values.get(i) : values[i];
  }
  function rowsFrom(series) {
    var out = [];
    (series || []).forEach(function (frame) {
      var fields = frame.fields || [];
      var n = frame.length || (fields[0] ? (fields[0].values.length || 0) : 0);
      for (var i = 0; i < n; i++) {
        var row = {};
        fields.forEach(function (f) {
          if (f.labels) Object.keys(f.labels).forEach(function (k) { row[k] = f.labels[k]; });
          row[f.name] = valueAt(f.values, i);
        });
        if (row.code) out.push(row);
      }
    });
    var seen = {};
    return out.filter(function (r) { if (seen[r.code]) return false; seen[r.code] = 1; return true; })
              .sort(function (a, b) { return a.code < b.code ? -1 : 1; });
  }

  // ---------------------------------------------------------------- styling (theme-neutral)
  var S = {
    wrap: { fontSize: 13, height: '100%', overflow: 'auto', padding: 4 },
    who: { opacity: 0.75, marginBottom: 8 },
    card: { border: '1px solid rgba(128,128,128,0.35)', borderRadius: 4, padding: '8px 10px', marginBottom: 8 },
    head: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' },
    code: { fontWeight: 600, fontSize: 14, minWidth: 90 },
    dim: { opacity: 0.75 },
    btn: { padding: '3px 10px', borderRadius: 3, border: '1px solid rgba(128,128,128,0.6)', background: 'transparent',
           color: 'inherit', cursor: 'pointer', fontSize: 12 },
    primary: { background: '#3d71d9', borderColor: '#3d71d9', color: '#fff' },
    danger: { background: '#c4314b', borderColor: '#c4314b', color: '#fff' },
    input: { padding: '3px 6px', fontSize: 12, borderRadius: 3, border: '1px solid rgba(128,128,128,0.6)',
             background: 'transparent', color: 'inherit', width: 220 },
    appr: { marginTop: 6, padding: '6px 8px', background: 'rgba(128,128,128,0.1)', borderRadius: 3,
            display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
    ok: { color: '#3fa34d' }, warn: { color: '#e0a400' }, bad: { color: '#e0475f' },
    msg: { marginTop: 6, fontSize: 12 }
  };
  function stateStyle(state) {
    if (state === 'in_sync') return S.ok;
    if (state === 'suspended' || state === 'plugin_down') return S.bad;
    return S.warn;
  }
  function badge(state) { return h('span', { style: Object.assign({ fontWeight: 600 }, stateStyle(state)) }, state || '?'); }

  // two-step button: first click arms, second click (within 6 s) acts — never a browser dialog
  function ConfirmButton(p) {
    var st = useState(false), armed = st[0], setArmed = st[1];
    useEffect(function () { if (!armed) return undefined; var t = setTimeout(function () { setArmed(false); }, 6000); return function () { clearTimeout(t); }; }, [armed]);
    return h('button', {
      style: Object.assign({}, S.btn, armed ? (p.danger ? S.danger : S.primary) : {}),
      disabled: p.busy,
      onClick: function () { if (!armed) { setArmed(true); return; } setArmed(false); p.onConfirm(); }
    }, armed ? 'Confirm: ' + p.label : p.label);
  }

  // ---------------------------------------------------------------- panel
  function Panel(props) {
    var rows = rowsFrom(props.data && props.data.series);
    var s1 = useState(null), session = s1[0], setSession = s1[1];
    var s2 = useState([]), types = s2[0], setTypes = s2[1];
    var s3 = useState([]), approvals = s3[0], setApprovals = s3[1];
    var s4 = useState(null), loadErr = s4[0], setLoadErr = s4[1];
    var s5 = useState({}), msgs = s5[0], setMsgs = s5[1];
    var s6 = useState(''), busy = s6[0], setBusy = s6[1];
    var s7 = useState({}), notes = s7[0], setNotes = s7[1];

    var load = useCallback(function () {
      return call('GET', '/bff/session').then(function (sess) {
        if (!sess || !sess.authenticated) throw new Error('Not signed in to FSP — reload the page.');
        setSession(sess);
        var tid = sess.user.tenantId;
        return Promise.all([
          call('GET', '/api/platform/plugins'),
          Promise.all(OPS.map(function (op) {
            return call('GET', '/api/approvals?tenantId=' + encodeURIComponent(tid) + '&status=PENDING_CHECKER&operationType=' + op + '&size=100')
              .catch(function () { return []; });
          }))
        ]);
      }).then(function (res) {
        setTypes(res[0] || []);
        setApprovals([].concat.apply([], res[1] || []));
        setLoadErr(null);
      }).catch(function (e) { setLoadErr(e.message); });
    }, []);
    useEffect(function () { load(); var t = setInterval(load, 30000); return function () { clearInterval(t); }; }, [load]);

    function note(code, text, isErr) { var m = Object.assign({}, msgs); m[code] = { text: text, err: !!isErr }; setMsgs(m); }
    function act(code, label, fn) {
      setBusy(code);
      fn().then(function (r) { note(code, label + ' — ' + (r || 'done')); })
        .catch(function (e) { note(code, label + ' failed: ' + e.message, true); })
        .then(function () { setBusy(''); return load(); });
    }

    function register(r) {
      act(r.code, 'Registration submitted', function () {
        return fetch('/ops/manifests/' + encodeURIComponent(r.ops_manifest), { credentials: 'same-origin' })
          .then(function (res) { if (!res.ok) throw new Error('manifest ' + r.ops_manifest + ' not readable (' + res.status + ')'); return res.text(); })
          .then(function (text) {
            var m = JSON.parse(text);
            return sha256Hex(text).then(function (hash) {
              return call('POST', '/api/platform/plugins', { code: m.code, version: m.version, baseUrl: r.base_url,
                                                            manifest: text, manifestHash: hash });
            });
          }).then(function () { return 'a different person must approve it below'; });
      });
    }
    function lifecycle(r, what) {
      act(r.code, what === 'suspend' ? 'Suspension requested' : 'Resume requested', function () {
        return call('POST', '/api/platform/plugins/' + encodeURIComponent(r.code) + '/' + what, { note: notes[r.code] || '' })
          .then(function () { return 'a different person must approve it below'; });
      });
    }
    function decide(code, a, approve) {
      act(code, approve ? 'Approved' : 'Rejected', function () {
        return call('POST', '/api/approvals/' + a.id + '/' + (approve ? 'approve' : 'reject'),
                    approve ? {} : { reason: notes[code] || 'rejected in the ops console' })
          .then(function () { return approve ? 'applied — the gateway follows within 60 s' : 'request closed'; });
      });
    }

    var who = session && session.user
      ? h('div', { style: S.who }, 'Signed in as ', h('b', null, session.user.username),
          ' — every action is decided by core: you need plugin.register to request, plugin.manage to approve, and the approver must be a different person.')
      : null;

    if (!rows.length) {
      return h('div', { style: S.wrap }, who, loadErr ? h('div', { style: S.bad }, loadErr) : null,
               h('div', { style: S.dim }, 'No plugin data yet (observe.py writes fsp_plugin_drift every 5 minutes).'));
    }

    return h('div', { style: S.wrap }, who,
      loadErr ? h('div', { style: Object.assign({}, S.bad, { marginBottom: 6 }) }, loadErr) : null,
      rows.map(function (r) {
        var mine = types.filter(function (t) { return t.code === r.code; });
        var ids = {}; mine.forEach(function (t) { ids[t.id] = t; });
        var pend = approvals.filter(function (a) { return ids[a.targetResourceId]; });
        var registered = mine.some(function (t) { return t.status === 'REGISTERED'; });
        var suspended = !registered && mine.some(function (t) { return t.status === 'SUSPENDED'; });
        var pendingKinds = {}; pend.forEach(function (a) { pendingKinds[a.operationType] = 1; });
        var m = msgs[r.code];
        var isBusy = busy === r.code;
        var buttons = [];
        if (REGISTERABLE[r.state] && !pendingKinds.PLUGIN_REGISTER && r.ops_manifest) {
          buttons.push(h(ConfirmButton, { key: 'reg', label: 'Register ' + r.shipped_version, busy: isBusy, onConfirm: function () { register(r); } }));
        }
        if (registered && !pendingKinds.PLUGIN_SUSPEND) {
          buttons.push(h(ConfirmButton, { key: 'sus', label: 'Suspend', danger: true, busy: isBusy, onConfirm: function () { lifecycle(r, 'suspend'); } }));
        }
        if (suspended && !pendingKinds.PLUGIN_RESUME) {
          buttons.push(h(ConfirmButton, { key: 'res', label: 'Resume', busy: isBusy, onConfirm: function () { lifecycle(r, 'resume'); } }));
        }
        return h('div', { key: r.code, style: S.card },
          h('div', { style: S.head },
            h('span', { style: S.code }, r.code),
            badge(r.state),
            h('span', { style: S.dim }, 'ships ' + (r.shipped_version || '?') + ' · registered ' + (r.registered_version || 'none') +
              (r.registered_via ? ' (by ' + r.registered_via + ')' : '')),
            h('span', { style: { flex: 1 } }),
            buttons.length ? h('input', { style: S.input, placeholder: 'note (why) — optional', value: notes[r.code] || '',
              onChange: function (e) { var n = Object.assign({}, notes); n[r.code] = e.target.value; setNotes(n); } }) : null,
            buttons),
          pend.map(function (a) {
            return h('div', { key: a.id, style: S.appr },
              h('b', null, a.operationType.replace('PLUGIN_', '').toLowerCase()),
              h('span', { style: S.dim }, (ids[a.targetResourceId] ? ids[a.targetResourceId].version + ' · ' : '') +
                'requested by ' + (a.makerName || a.makerId) + ' · ' + new Date(a.requestedAt).toLocaleString()),
              h('span', { style: { flex: 1 } }),
              a.canDecide
                ? [h(ConfirmButton, { key: 'ok', label: 'Approve', busy: isBusy, onConfirm: function () { decide(r.code, a, true); } }),
                   h(ConfirmButton, { key: 'no', label: 'Reject', danger: true, busy: isBusy, onConfirm: function () { decide(r.code, a, false); } })]
                : h('span', { style: S.dim }, a.blockedReason || 'another person must decide this'));
          }),
          m ? h('div', { style: Object.assign({}, S.msg, m.err ? S.bad : S.ok) }, m.text) : null);
      }));
  }

  return { plugin: new grafanaData.PanelPlugin(Panel) };
});
