// CC GUI Tweaks: дополнения к чату CC GUI. Внедряется плагином через executeJavaScript во все JCEF-браузеры IDE,
// работает только на странице CC GUI. Данные приходят вызовами window.__cct.settings / spotify / limits,
// команды и настройки уходят в Java сообщением консоли "__cct:*".
//
//   Spotify  — карточка плеера над панелью «Задачи / Субагент / Правки» (или над полем ввода), перемотка,
//              очередь и плейлист текущего трека (через Spotify Web API, данные — window.__cct.queue);
//   Лимиты   — бар расхода 5-часового окна и недельных лимитов Claude там же;
//   Настройки — свой пункт «CC GUI Tweaks» в боковом меню настроек CC GUI.
(function () {
  // Бандл CC GUI глушит console.log/debug/info/warn пустышками, поэтому пишем через console.context():
  // это отдельный нативный объект консоли, его переопределения не задевают.
  function send(m) {
    try { if (console.context) { (window.__cctOut || (window.__cctOut = console.context('cc-gui-tweaks'))).info('__cct:' + m); return; } } catch (e) {}
    console.error('__cct:' + m);
  }
  if (window.__cctOn) { if (window.__cct) send('ready'); return; }

  function isCcGui() {
    return !!document.getElementById('app') &&
      (!!document.querySelector('meta[http-equiv="Content-Security-Policy"][content*="cc-gui"]') || /Claude Chat/i.test(document.title));
  }

  var BAR_BG = 'var(--color-chat-bars-bg,var(--bg-secondary,#252526))';
  var BAR_BORDER = 'var(--color-chat-bars-border,var(--border-primary,#333))';
  var MUTED = 'var(--text-tertiary,#858585)';
  var CSS =
    // ---- общий контейнер над панелью вкладок / полем ввода ----
    '#cct-bars{display:none;flex:none;flex-direction:column;gap:6px;margin:0 20px 6px;' +
    'font:12px/1.25 var(--cc-gui-ui-font-family,"Inter",system-ui,sans-serif);user-select:none}' +
    'html.cct-sp-on #cct-bars,html.cct-lim-on #cct-bars,html.cct-sp-min #cct-bars{display:flex}' +
    // свёрнутый плеер стоит в одной строке с баром лимитов, слева от него
    '#cct-brow{display:none;gap:6px;min-width:0}' +
    'html.cct-lim-on #cct-brow,html.cct-sp-min #cct-brow{display:flex}' +
    '#cct-brow > #cct-lim{flex:1 1 auto;min-width:0}' +
    '#cct-spm{display:none;flex:none;width:28px;height:28px;padding:0;justify-content:center;cursor:pointer;color:#1db954;' +
    'font:inherit;transition:background .15s,color .15s}' +
    'html.cct-sp-min #cct-spm{display:flex}' +
    '#cct-spm:hover{background:var(--color-chat-bars-hover-bg,var(--bg-hover,#35373c))}' +
    '#cct-spm:focus-visible{outline:1px solid var(--accent-primary,#0078d4);outline-offset:-1px}' +
    '#cct-spm.cct-paused{color:' + MUTED + '}' +
    '#cct-spm.cct-paused:hover{color:#1db954}' +
    '#cct-spm svg{width:16px;height:16px;fill:currentColor}' +
    '.cct-card{position:relative;box-sizing:border-box;align-items:center;background:' + BAR_BG + ';border:1px solid ' + BAR_BORDER + ';' +
    'border-radius:6px;color:var(--color-chat-bars-text,var(--text-primary,#ccc));overflow:hidden}' +
    '.cct-card button{flex:none;width:24px;height:24px;padding:0;border:0;border-radius:4px;background:transparent;' +
    'color:var(--text-primary,#ccc);cursor:pointer;display:grid;place-items:center}' +
    '.cct-card button:hover{background:var(--color-chat-bars-hover-bg,var(--bg-hover,#35373c))}' +
    '.cct-card button svg{width:14px;height:14px;fill:currentColor}' +

    // ---- Spotify (оформление как у .status-panel-tabs) ----
    '#cct-sp{display:none;height:38px;padding:0 4px 0 5px;gap:8px}' +
    'html.cct-sp-on #cct-sp{display:flex}' +
    '#cct-sp .cct-cover{width:26px;height:26px;flex:none;border-radius:4px;background:var(--bg-tertiary,#2b2d31) center/cover no-repeat;' +
    'display:grid;place-items:center;color:#1db954;font-size:13px}' +
    '#cct-sp .cct-cover.cct-zoom{cursor:zoom-in}' +
    '#cct-sp .cct-meta{flex:1;min-width:0;display:flex;flex-direction:column;gap:1px}' +
    '#cct-sp .cct-title,#cct-sp .cct-sub{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '#cct-sp .cct-title{font-weight:600;color:var(--text-secondary,#e0e0e0)}' +
    '#cct-sp .cct-sub{font-size:11px;color:' + MUTED + '}' +
    '#cct-sp .cct-time{flex:none;font-size:11px;color:' + MUTED + ';font-variant-numeric:tabular-nums}' +
    '#cct-sp button.cct-x{color:' + MUTED + '}' +
    '#cct-sp button.cct-x svg{width:11px;height:11px}' +
    // перемотка: невидимая полоса 7px у нижнего края, при наведении шкала толще и подсказка со временем
    '#cct-sp .cct-prog{position:absolute;left:0;right:0;bottom:0;height:7px;cursor:pointer;touch-action:none}' +
    '#cct-sp.cct-nodur .cct-prog{cursor:default;pointer-events:none}' +
    '#cct-sp .cct-prog i,#cct-sp .cct-prog s{position:absolute;left:0;bottom:0;height:2px;width:0;transition:height .12s}' +
    // плеер обновляется раз в 0,5 с — линейный переход той же длины делает движение плавным
    '#cct-sp .cct-prog i{background:#1db954;transition:width .5s linear,height .12s}' +
    '#cct-sp .cct-prog s{display:none;background:var(--text-tertiary,#858585);opacity:.35}' +
    '#cct-sp .cct-prog:hover i,#cct-sp .cct-prog:hover s,#cct-sp.cct-seeking .cct-prog i{height:4px}' +
    '#cct-sp .cct-prog:hover s{display:block}' +
    '#cct-sp.cct-seeking .cct-prog i{transition:none}#cct-sp.cct-seeking .cct-prog s{display:none}' +
    '#cct-sp .cct-seektip{position:absolute;bottom:8px;left:0;transform:translateX(-50%);display:none;padding:1px 5px;border-radius:3px;' +
    'background:var(--bg-primary,#1e1e1e);border:1px solid ' + BAR_BORDER + ';font-size:10px;line-height:14px;font-variant-numeric:tabular-nums;' +
    'color:var(--text-secondary,#e0e0e0);pointer-events:none;white-space:nowrap}' +
    '#cct-sp.cct-tip .cct-seektip{display:block}' +
    '#cct-sp.cct-paused .cct-prog i{background:' + MUTED + '}' +
    '#cct-sp.cct-paused .cct-title{color:' + MUTED + '}' +
    '#cct-sp.cct-notime .cct-time,#cct-sp.cct-novol .cct-vol,#cct-sp.cct-noq button.cct-q{display:none}' +
    // размер 2 — крупный плеер
    '#cct-sp.cct-lg{height:52px;padding:0 6px 0 6px;gap:6px}' +
    '#cct-sp.cct-lg .cct-cover{width:40px;height:40px;border-radius:5px;font-size:18px;margin-right:4px}' +
    '#cct-sp.cct-lg .cct-time{margin:0 4px}' +
    '#cct-sp.cct-lg .cct-meta{gap:3px}' +
    '#cct-sp.cct-lg .cct-title{font-size:14px}' +
    '#cct-sp.cct-lg .cct-sub,#cct-sp.cct-lg .cct-time{font-size:12px}' +
    '#cct-sp.cct-lg button{width:30px;height:30px}' +
    '#cct-sp.cct-lg button svg{width:17px;height:17px}' +
    '#cct-sp.cct-lg button.cct-x svg{width:13px;height:13px}' +
    '#cct-sp.cct-lg .cct-prog{height:9px}' +
    '#cct-sp.cct-lg .cct-prog i,#cct-sp.cct-lg .cct-prog s{height:3px}' +
    '#cct-sp.cct-lg .cct-prog:hover i,#cct-sp.cct-lg .cct-prog:hover s,#cct-sp.cct-lg.cct-seeking .cct-prog i{height:5px}' +
    '#cct-sp.cct-lg .cct-seektip{bottom:10px;font-size:11px}' +
    '#cct-sp.cct-lg .cct-vol:hover input,#cct-sp.cct-lg .cct-vol:focus-within input,#cct-sp.cct-lg .cct-vol.cct-drag input{width:80px}' +
    '#cct-sp.cct-lg .cct-vol input::-webkit-slider-thumb{width:12px;height:12px}' +
    '#cct-sp button.cct-q.cct-on{color:#1db954}' +
    // громкость: кнопка без звука + ползунок, который выезжает при наведении
    '#cct-sp .cct-vol{flex:none;display:flex;align-items:center}' +
    '#cct-sp .cct-vol input{-webkit-appearance:none;appearance:none;width:0;opacity:0;margin:0;height:4px;border-radius:2px;cursor:pointer;' +
    'background:linear-gradient(to right,#1db954 var(--p,0%),var(--bg-tertiary,#3a3a3a) var(--p,0%));transition:width .15s,opacity .15s,margin .15s}' +
    '#cct-sp .cct-vol:hover input,#cct-sp .cct-vol:focus-within input,#cct-sp .cct-vol.cct-drag input{width:64px;opacity:1;margin:0 4px 0 2px}' +
    '#cct-sp .cct-vol input::-webkit-slider-thumb{-webkit-appearance:none;width:10px;height:10px;border-radius:50%;background:var(--text-primary,#ddd);border:0}' +
    '#cct-sp .cct-vol.cct-muted input{background:linear-gradient(to right,' + MUTED + ' var(--p,0%),var(--bg-tertiary,#3a3a3a) var(--p,0%))}' +
    '#cct-sp .cct-vol.cct-muted button{color:' + MUTED + '}' +
    '@media (max-width:360px){#cct-sp .cct-time,#cct-sp button.cct-prev{display:none}}' +
    '@media (max-width:300px){#cct-sp .cct-vol{display:none}}' +
    // узко: кнопок много (с текстом песни — семь), поджимаем отступы, у крупного плеера и сами кнопки
    '@media (max-width:360px){#cct-sp{gap:5px}#cct-sp.cct-lg{gap:4px}#cct-sp.cct-lg button{width:26px}}' +

    // ---- обложка во весь размер ----
    '#cct-lightbox{position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;' +
    'background:rgba(0,0,0,.72);cursor:zoom-out;animation:cct-fade .15s ease-out;' +
    'font:13px/1.35 var(--cc-gui-ui-font-family,"Inter",system-ui,sans-serif)}' +
    '#cct-lightbox img{width:min(80vw,70vh,420px);height:auto;aspect-ratio:1;object-fit:cover;border-radius:8px;box-shadow:0 12px 40px rgba(0,0,0,.5)}' +
    '#cct-lightbox .cct-cap{max-width:min(80vw,420px);text-align:center;color:#fff}' +
    '#cct-lightbox .cct-cap b{display:block;font-weight:600}' +
    '#cct-lightbox .cct-cap span{color:rgba(255,255,255,.65);font-size:12px}' +
    '@keyframes cct-fade{from{opacity:0}to{opacity:1}}' +

    // ---- очередь и плейлист (всплывает над карточкой плеера) ----
    '#cct-queue,#cct-lyrics{position:fixed;z-index:9999;display:flex;flex-direction:column;box-sizing:border-box;min-height:120px;' +
    'background:var(--bg-primary,#1e1e1e);border:1px solid ' + BAR_BORDER + ';border-radius:8px;box-shadow:0 8px 28px rgba(0,0,0,.35);' +
    'color:var(--text-primary,#ccc);font:12px/1.3 var(--cc-gui-ui-font-family,"Inter",system-ui,sans-serif);animation:cct-fade .12s ease-out;overflow:hidden}' +
    '#cct-queue .cct-qh,#cct-lyrics .cct-qh{flex:none;display:flex;align-items:center;gap:4px;padding:6px 6px 6px 12px;border-bottom:1px solid ' + BAR_BORDER + '}' +
    '#cct-queue .cct-qh b,#cct-lyrics .cct-qh b{flex:1;font-size:12px;font-weight:600;color:var(--text-secondary,#e0e0e0)}' +
    '#cct-queue .cct-qh button,#cct-lyrics .cct-qh button{width:24px;height:24px;padding:0;border:0;border-radius:4px;background:transparent;color:' + MUTED + ';cursor:pointer;display:grid;place-items:center}' +
    '#cct-queue .cct-qh button:hover,#cct-lyrics .cct-qh button:hover{background:var(--bg-hover,#35373c);color:var(--text-primary,#ccc)}' +
    '#cct-queue .cct-qh .codicon,#cct-lyrics .cct-qh .codicon{font-size:14px}' +
    '#cct-queue .cct-qb{flex:1;min-height:0;overflow-y:auto;padding:4px 0 8px}' +
    '#cct-queue .cct-qs{padding:8px 12px 4px;font-size:11px;font-weight:600;color:' + MUTED + ';text-transform:uppercase;letter-spacing:.04em}' +
    '#cct-queue .cct-qr{display:flex;align-items:center;gap:10px;padding:4px 12px;min-width:0}' +
    '#cct-queue .cct-qr:hover{background:var(--bg-hover,#2a2d2e)}' +
    '#cct-queue .cct-qi{flex:none;width:32px;height:32px;border-radius:3px;background:var(--bg-tertiary,#2b2d31) center/cover no-repeat;' +
    'display:grid;place-items:center;color:' + MUTED + ';font-size:12px}' +
    '#cct-queue .cct-qm{flex:1;min-width:0}' +
    '#cct-queue .cct-qn,#cct-queue .cct-qa{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '#cct-queue .cct-qn{color:var(--text-secondary,#e0e0e0)}' +
    '#cct-queue .cct-qa{font-size:11px;color:' + MUTED + '}' +
    '#cct-queue .cct-qd{flex:none;font-size:11px;color:' + MUTED + ';font-variant-numeric:tabular-nums}' +
    '#cct-queue .cct-qr.cct-now .cct-qn{color:#1db954;font-weight:600}' +
    '#cct-queue .cct-qc{margin:4px 8px;padding:6px 8px;border-radius:6px;background:var(--bg-secondary,#252526)}' +
    '#cct-queue .cct-qc:hover{background:var(--bg-secondary,#252526)}' +
    '#cct-queue .cct-qc .cct-qi{width:40px;height:40px}' +
    '#cct-queue a.cct-qn{display:block;cursor:pointer;text-decoration:none;font-weight:600}' +
    '#cct-queue a.cct-qn:hover{text-decoration:underline;color:#1db954}' +
    '#cct-queue .cct-qe{padding:16px 14px;color:' + MUTED + ';line-height:1.45}' +
    '#cct-queue .cct-qe b{color:var(--text-secondary,#e0e0e0);font-weight:600}' +
    '#cct-queue .cct-qr.cct-qp{cursor:pointer}' +
    '#cct-queue .cct-qi{position:relative}' +
    '#cct-queue .cct-qp .cct-qi::after,#cct-queue .cct-qbusy .cct-qi::after{content:"";position:absolute;inset:0;border-radius:inherit;background:rgba(0,0,0,.55);opacity:0}' +
    '#cct-queue .cct-qi > .cct-qpi{position:absolute;inset:0;display:grid;place-items:center;color:#fff;opacity:0;z-index:1}' +
    '#cct-queue .cct-qi > .cct-qpi svg{width:16px;height:16px;fill:currentColor}' +
    '#cct-queue .cct-qp:hover .cct-qi::after,#cct-queue .cct-qp:hover .cct-qpi,#cct-queue .cct-qbusy .cct-qi::after,#cct-queue .cct-qbusy .cct-qpi{opacity:1}' +
    '#cct-queue .cct-qp:hover .cct-qn{color:#1db954}' +
    '#cct-queue .cct-qbusy .cct-qpi .codicon{font-size:14px}' +
    '#cct-queue .cct-busy .cct-qr.cct-qp:not(.cct-qbusy){opacity:.5;cursor:default}' +
    '#cct-queue .cct-qnote{padding:0 12px 6px;font-size:11px;color:' + MUTED + ';line-height:1.4}' +
    '#cct-queue .cct-qw{margin:4px 8px 6px;padding:6px 10px;border-radius:6px;font-size:12px;line-height:1.4;' +
    'background:rgba(204,167,0,.14);color:var(--text-secondary,#e0e0e0);border:1px solid rgba(204,167,0,.35)}' +

    // ---- текст песни (та же всплывающая панель над плеером) ----
    '#cct-sp button.cct-ly.cct-on{color:#1db954}' +
    '#cct-sp.cct-noly button.cct-ly{display:none}' +
    '#cct-lyrics .cct-qh b{min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '#cct-lyrics .cct-yb{position:relative;flex:1;min-height:0;overflow-y:auto;padding:12px 16px 96px}' +
    '#cct-lyrics .cct-yl{padding:3px 0;font-size:15px;line-height:1.4;font-weight:700;color:' + MUTED + ';cursor:pointer;transition:color .25s,opacity .25s}' +
    '#cct-lyrics .cct-yl:hover{color:var(--text-secondary,#e0e0e0)}' +
    '#cct-lyrics .cct-yl.cct-past{color:var(--text-secondary,#e0e0e0);opacity:.45}' +
    '#cct-lyrics .cct-yl.cct-cur{color:#1db954;opacity:1}' +
    '#cct-lyrics .cct-plain{padding-bottom:16px}' +
    '#cct-lyrics .cct-plain .cct-yl{font-size:13px;font-weight:400;color:var(--text-secondary,#e0e0e0);cursor:text;user-select:text;min-height:9px}' +
    '#cct-lyrics .cct-ye{padding:8px 0;color:' + MUTED + ';line-height:1.45}' +
    '#cct-lyrics .cct-ye .codicon{font-size:13px;vertical-align:-2px;margin-right:4px}' +
    '#cct-lyrics .cct-yn{padding:0 0 8px;font-size:11px}' +
    '#cct-lyrics button.cct-yr{margin-top:8px;height:22px;padding:0 10px;border:1px solid ' + BAR_BORDER + ';border-radius:4px;background:transparent;' +
    'color:var(--text-secondary,#e0e0e0);font:inherit;font-size:11px;cursor:pointer}' +
    '#cct-lyrics button.cct-yr:hover{background:var(--bg-hover,#35373c)}' +
    '#cct-lyrics .cct-yf{flex:none;padding:6px 12px 7px;font-size:12px;line-height:1.35;color:' + MUTED + ';border-top:1px solid ' + BAR_BORDER + '}' +

    // ---- лимиты ----
    '#cct-lim{display:none;height:28px;padding:0 8px;gap:10px;font-size:11px;cursor:default;container-type:inline-size}' +
    'html.cct-lim-on #cct-lim{display:flex}' +
    '#cct-lim.cct-stale .cct-li{opacity:.6}' +
    '#cct-lim button.cct-lref{flex:none;display:inline-flex;align-items:center;gap:4px;width:auto;height:20px;padding:0 8px;white-space:nowrap;border:1px solid ' + BAR_BORDER + ';' +
    'border-radius:4px;background:transparent;color:var(--text-secondary,#e0e0e0);font:inherit;font-size:11px;cursor:pointer}' +
    '#cct-lim button.cct-lref:hover{background:var(--bg-hover,rgba(255,255,255,.08));color:var(--text-primary,#fff)}' +
    '#cct-lim button.cct-lref:disabled{cursor:default;opacity:.7;background:transparent}' +
    '#cct-lim .cct-lref .codicon{font-size:12px}' +
    '#cct-lim button.cct-lref.cct-ic{width:22px;padding:0;justify-content:center}' +
    '#cct-lim > .codicon{flex:none;font-size:13px;color:' + MUTED + '}' +
    '#cct-lim .cct-li{flex:1 1 0;min-width:0;display:flex;align-items:center;gap:6px}' +
    '#cct-lim .cct-li + .cct-li{border-left:1px solid ' + BAR_BORDER + ';padding-left:10px}' +
    '#cct-lim .cct-ll{flex:0 1 auto;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:' + MUTED + '}' +
    '#cct-lim .cct-lm{flex:1 1 0;min-width:14px;max-width:120px;height:4px;border-radius:2px;background:var(--bg-tertiary,#3a3a3a);overflow:hidden}' +
    '#cct-lim .cct-lm i{display:block;height:100%;border-radius:2px;background:var(--accent-primary,#0078d4)}' +
    '#cct-lim .cct-lp{flex:none;font-weight:600;font-variant-numeric:tabular-nums;color:var(--text-secondary,#e0e0e0)}' +
    '#cct-lim .cct-lr{flex:none;white-space:nowrap;color:' + MUTED + ';font-variant-numeric:tabular-nums}' +
    '#cct-lim .cct-warn .cct-lm i{background:#e5a50a}#cct-lim .cct-warn .cct-lp{color:#e5a50a}' +
    '#cct-lim .cct-crit .cct-lm i{background:#e5484d}#cct-lim .cct-crit .cct-lp{color:#e5484d}' +
    '#cct-lim .cct-msg{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:' + MUTED + ';cursor:pointer}' +
    // время сброса прячется, если ему не хватает места (cct-tight ставит fitLimits) или оно выключено
    '#cct-lim.cct-noreset .cct-lr,#cct-lim.cct-tight .cct-lr{display:none}' +
    '@container (max-width:260px){#cct-lim .cct-lm{display:none}}' +

    // ---- раздел в настройках CC GUI ----
    // Пока открыт наш раздел, родной контент скрыт, а подсветка родного пункта снята.
    'html.cct-page [class*="_settingsContent_"]:not(#cct-panel){display:none!important}' +
    '#cct-panel{display:none}html.cct-page #cct-panel{display:block}' +
    'html.cct-page [class*="_sidebarItem_"][class*="_active_"]:not(.cct-nav){background:transparent;color:var(--text-primary);font-weight:inherit}' +
    'html.cct-page [class*="_sidebarItem_"][class*="_active_"]:not(.cct-nav):hover{background:var(--bg-hover)}' +
    'html.cct-page [class*="_sidebarItem_"][class*="_active_"]:not(.cct-nav):before{content:none}' +
    '.cct-nav.cct-nav-on{background:var(--accent-primary);color:#fff;font-weight:600}' +
    '.cct-nav.cct-nav-on:hover{background:var(--accent-primary)}' +
    '.cct-nav.cct-nav-on:before{content:"";position:absolute;left:0;top:0;bottom:0;width:3px;background:#fff}' +
    // как ._configSection/_sectionTitle/_fieldHeader/_toggle* у CC GUI
    '#cct-panel .cct-sec{max-width:800px;padding-bottom:24px}' +
    '#cct-panel h3{font-size:20px;font-weight:600;margin:0 0 8px;color:var(--text-primary)}' +
    '#cct-panel .cct-desc{color:var(--text-tertiary);margin:0 0 20px;font-size:13px;line-height:1.45}' +
    '#cct-panel .cct-mod{margin-bottom:24px;padding:16px;background:var(--bg-secondary);border:1px solid var(--border-secondary);border-radius:8px}' +
    '#cct-panel .cct-head{display:flex;align-items:center;gap:8px;margin-bottom:6px}' +
    '#cct-panel .cct-head .codicon{font-size:16px;color:var(--accent-primary)}' +
    '#cct-panel .cct-label{font-size:14px;font-weight:600;color:var(--text-secondary)}' +
    '#cct-panel .cct-hint{font-size:12px;color:var(--text-tertiary);margin:0 0 14px;line-height:1.45}' +
    '#cct-panel .cct-tg{display:flex;align-items:center;gap:12px;cursor:pointer;margin-bottom:10px;width:fit-content}' +
    '#cct-panel .cct-tg input{position:absolute;opacity:0;width:0;height:0}' +
    '#cct-panel .cct-sl{position:relative;flex:none;width:44px;height:24px;box-sizing:border-box;background:var(--bg-tertiary);' +
    'border:1px solid var(--border-secondary);border-radius:12px;transition:all .2s}' +
    '#cct-panel .cct-sl:after{content:"";position:absolute;top:2px;left:2px;width:18px;height:18px;background:var(--text-tertiary);border-radius:50%;transition:all .2s}' +
    '#cct-panel .cct-tg input:checked + .cct-sl{background:var(--accent-primary);border-color:var(--accent-primary)}' +
    '#cct-panel .cct-tg input:checked + .cct-sl:after{left:22px;background:#fff}' +
    '#cct-panel .cct-tg input:focus-visible + .cct-sl{outline:1px solid var(--accent-primary);outline-offset:2px}' +
    '#cct-panel .cct-tl{font-size:13px;color:var(--text-secondary)}' +
    '#cct-panel .cct-sub{margin:4px 0 0 18px;padding-left:16px;border-left:2px solid var(--border-secondary)}' +
    '#cct-panel .cct-off .cct-sub{opacity:.45;pointer-events:none}' +
    '#cct-panel .cct-row{display:flex;align-items:center;gap:12px;margin-bottom:10px;font-size:13px;color:var(--text-secondary)}' +
    '#cct-panel .cct-seg{display:flex;background:var(--bg-tertiary);padding:3px;border-radius:6px;border:1px solid var(--border-secondary)}' +
    '#cct-panel .cct-seg button{padding:4px 12px;border:0;border-radius:4px;background:transparent;color:var(--text-primary);font-size:12px;cursor:pointer}' +
    '#cct-panel .cct-seg button:hover{background:var(--bg-hover)}' +
    '#cct-panel .cct-seg button.cct-sel{background:var(--accent-primary);color:#fff}' +
    '#cct-panel .cct-status{margin-top:12px;font-size:12px;color:var(--text-tertiary);display:flex;align-items:center;gap:6px;min-height:16px}' +
    '#cct-panel .cct-status .codicon{font-size:14px}' +
    '#cct-panel .cct-status.cct-ok .codicon{color:var(--color-success,#3fb950)}' +
    '#cct-panel .cct-status.cct-bad .codicon{color:var(--color-error,#f85149)}' +
    '#cct-panel .cct-off .cct-status{display:none}' +
    '#cct-panel .cct-ver{font-size:12px;color:var(--text-tertiary)}' +
    '#cct-panel .cct-web{margin:6px 0 10px;padding:12px 14px;border:1px solid var(--border-secondary);border-radius:6px;max-width:560px}' +
    '#cct-panel .cct-wl{font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:6px}' +
    '#cct-panel .cct-steps{margin:0 0 12px;padding-left:20px;font-size:12px;line-height:1.7;color:var(--text-tertiary)}' +
    '#cct-panel .cct-steps b{color:var(--text-secondary);font-weight:600}' +
    '#cct-panel .cct-steps a{color:var(--accent-primary);cursor:pointer;text-decoration:none}#cct-panel .cct-steps a:hover{text-decoration:underline}' +
    '#cct-panel .cct-steps code{font:11px var(--cc-gui-code-font-family,monospace);padding:1px 5px;border-radius:3px;background:var(--bg-tertiary);color:var(--text-secondary);user-select:all}' +
    '#cct-panel .cct-steps button{margin-left:4px;padding:0 6px;height:18px;font-size:11px;border:1px solid var(--border-secondary);border-radius:3px;background:transparent;color:var(--text-secondary);cursor:pointer}' +
    '#cct-panel .cct-steps button:hover{background:var(--bg-hover)}' +
    '#cct-panel .cct-web.cct-web-on .cct-steps,#cct-panel .cct-web.cct-web-on .cct-in,#cct-panel .cct-web.cct-web-on [data-a="login"]{display:none}' +
    '#cct-panel .cct-web:not(.cct-web-on) [data-a="logout"]{display:none}' +
    '#cct-panel .cct-web.cct-web-on.cct-web-scope [data-a="login"]{display:inline-block}' +
    '#cct-panel .cct-in{width:290px;max-width:100%;box-sizing:border-box;padding:5px 8px;border:1px solid var(--border-secondary);border-radius:4px;' +
    'background:var(--bg-tertiary);color:var(--text-primary);font:12px var(--cc-gui-code-font-family,monospace);outline:none}' +
    '#cct-panel .cct-in:focus{border-color:var(--accent-primary)}' +
    '#cct-panel .cct-btn{padding:5px 14px;border:1px solid var(--accent-primary);border-radius:4px;background:var(--accent-primary);color:#fff;font-size:12px;cursor:pointer}' +
    '#cct-panel .cct-btn:hover{filter:brightness(1.1)}' +
    '#cct-panel .cct-btn.cct-btn2{background:transparent;color:var(--text-secondary);border-color:var(--border-secondary)}' +
    '#cct-panel .cct-btn.cct-btn2:hover{background:var(--bg-hover);filter:none}' +
    '#cct-panel .cct-web .cct-row{flex-wrap:wrap;margin-bottom:0}' +
    '#cct-panel .cct-web .cct-status{margin-top:8px}' +
    '#cct-panel .cct-web .cct-wp{margin:0 0 8px;color:var(--text-tertiary);font-size:12px;line-height:1.45}' +
    '#cct-panel .cct-web.cct-web-on .cct-wp{display:none}' +
    // «О плагине»
    '#cct-panel .cct-about{margin:0 0 24px;padding:16px;border:1px solid var(--accent-primary);border-radius:8px;background:var(--bg-secondary)}' +
    '#cct-panel .cct-ab-head{display:flex;align-items:center;gap:12px;margin-bottom:10px}' +
    '#cct-panel .cct-ab-logo{flex:none;width:36px;height:36px;border-radius:8px;display:grid;place-items:center;background:var(--accent-primary);color:#fff}' +
    '#cct-panel .cct-ab-logo .codicon{font-size:20px}' +
    '#cct-panel .cct-ab-name{font-size:14px;font-weight:600;color:var(--text-primary)}' +
    '#cct-panel .cct-ab-ver{font-size:12px;color:var(--text-tertiary);margin-top:2px}' +
    '#cct-panel .cct-ab-text{font-size:12px;line-height:1.5;color:var(--text-tertiary);margin:0 0 12px}' +
    '#cct-panel .cct-ab-row{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--text-tertiary);margin-bottom:12px}' +
    '#cct-panel .cct-ab-row b{color:var(--text-secondary);font-weight:600}' +
    '#cct-panel .cct-ab-ava{flex:none;width:32px;height:32px;border-radius:50%;object-fit:cover;box-shadow:0 0 0 1px var(--border-secondary)}' +
    '#cct-panel .cct-ab-links{display:flex;flex-wrap:wrap;gap:8px}' +
    '#cct-panel .cct-ab-links a{display:inline-flex;align-items:center;gap:6px;padding:5px 12px;border:1px solid var(--border-secondary);border-radius:4px;' +
    'font-size:12px;color:var(--text-secondary);cursor:pointer;text-decoration:none}' +
    '#cct-panel .cct-ab-links a:hover{background:var(--bg-hover);color:var(--text-primary)}' +
    '#cct-panel .cct-ab-links .codicon{font-size:14px}' +
    '#cct-panel .cct-ab-links small{color:var(--text-tertiary);font-size:11px}' +
    // вкладки
    '#cct-panel .cct-sec{container-type:inline-size}' +
    '#cct-panel .cct-tabs{display:flex;gap:2px;background:var(--bg-secondary);padding:4px;border-radius:6px;border:1px solid var(--border-secondary);margin-bottom:20px}' +
    '#cct-panel .cct-tab{display:flex;align-items:center;justify-content:center;gap:6px;flex:1 1 auto;min-width:0;padding:6px 14px;border:0;border-radius:4px;' +
    'background:transparent;color:var(--text-primary);font-size:13px;cursor:pointer;transition:background .2s,color .2s;white-space:nowrap}' +
    '#cct-panel .cct-tab:hover{background:var(--bg-hover)}' +
    '#cct-panel .cct-tab:focus-visible{outline:1px solid var(--accent-primary);outline-offset:-1px}' +
    '#cct-panel .cct-tab .codicon{flex:none;font-size:14px}' +
    '#cct-panel .cct-tab .cct-tn{overflow:hidden;text-overflow:ellipsis}' +
    '#cct-panel .cct-tab.cct-act{background:var(--button-primary-background,#0078d4);color:#fff}' +
    '#cct-panel .cct-dot{flex:none;width:6px;height:6px;border-radius:50%;background:var(--color-success,#3fb950);display:none}' +
    '#cct-panel .cct-tab.cct-on .cct-dot{display:block}' +
    '#cct-panel .cct-tab.cct-act .cct-dot{background:#fff}' +
    '#cct-panel [data-pane][hidden]{display:none}' +
    '@container (max-width:600px){#cct-panel .cct-tab{padding:6px 8px;font-size:12px;gap:5px}}' +
    // узко: у неактивных вкладок остаётся значок, подпись — в подсказке
    '@container (max-width:460px){#cct-panel .cct-tab.cct-act{flex:1 1 auto}' +
    '#cct-panel .cct-tab:not(.cct-act){position:relative;flex:0 0 36px;padding:6px 0}' +
    '#cct-panel .cct-tab:not(.cct-act) .cct-tn{display:none}' +
    '#cct-panel .cct-tab:not(.cct-act) .cct-dot{position:absolute;top:5px;right:6px;width:5px;height:5px}}' +
    // режим производительности: ключи реестра IDE
    '#cct-panel .cct-reg{margin:14px 0 0;padding:12px 14px;border:1px solid var(--border-secondary);border-radius:6px;max-width:560px}' +
    '#cct-panel .cct-reg[hidden]{display:none}' +
    '#cct-panel .cct-reg .cct-wl{display:flex;align-items:center;gap:6px}' +
    '#cct-panel .cct-reg .cct-wp{margin:0 0 12px;color:var(--text-tertiary);font-size:12px;line-height:1.45}' +
    '#cct-panel .cct-reg .cct-tg{margin-bottom:2px}' +
    '#cct-panel .cct-rn{margin:0 0 12px 56px;font-size:11px;line-height:1.4;color:var(--text-tertiary)}' +
    '#cct-panel .cct-rn b{color:#e5a50a;font-weight:600}' +
    '#cct-panel .cct-reg .cct-row{flex-wrap:wrap}' +
    '#cct-panel .cct-reg .cct-row.cct-rfps{margin-bottom:2px}' +
    '#cct-panel .cct-reg .cct-btns{display:flex;flex-wrap:wrap;gap:8px;margin-top:4px}' +
    '#cct-panel .cct-reg .cct-btn:disabled{opacity:.45;cursor:default;filter:none}' +
    '#cct-panel .cct-btn.cct-warnbtn{background:#c4314b;border-color:#c4314b}' +
    '#cct-panel .cct-off .cct-reg .cct-status{display:flex}' +
    // журнал лагов: тот же блок, работает и при выключенном режиме производительности
    '#cct-panel .cct-jr .cct-btns{margin-top:8px}' +
    '#cct-panel .cct-jr .cct-status span:last-child{overflow-wrap:anywhere}' +
    // режим производительности: размытие под диалогами
    'html.cct-perf-blur *,html.cct-perf-blur *::before,html.cct-perf-blur *::after{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}';

  var ICON = {
    prev: 'M6 6h2v12H6zm3.5 6 8.5 6V6z',
    next: 'M16 6h2v12h-2zM6 18l8.5-6L6 6z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 5h4v14H6zm8 0h4v14h-4z',
    lyrics: 'M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z',
    queue: 'M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18A3 3 0 0 0 16 14c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z',
    x: 'M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z',
    spotify: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.59 14.42a.62.62 0 0 1-.86.21c-2.35-1.44-5.3-1.76-8.78-.96a.62.62 0 1 1-.28-1.22c3.81-.87 7.08-.5 9.71 1.11.3.18.39.57.21.86zm1.22-2.72a.78.78 0 0 1-1.07.26c-2.69-1.65-6.79-2.13-9.97-1.17a.78.78 0 0 1-.45-1.49c3.63-1.1 8.15-.57 11.23 1.33.37.22.48.7.26 1.07zm.11-2.83C14.69 8.95 9.37 8.78 6.3 9.71a.94.94 0 1 1-.54-1.8c3.53-1.07 9.4-.86 13.1 1.33a.94.94 0 0 1-.94 1.63z',
    volHi: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
    volLo: 'M18.5 12A4.5 4.5 0 0 0 16 8v8a4.47 4.47 0 0 0 2.5-4zM5 9v6h4l5 5V4L9 9H5z',
    volOff: 'M16.5 12A4.5 4.5 0 0 0 14 8v2.18l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a9 9 0 0 0 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4 9.91 6.09 12 8.18V4z'
  };
  var root = document.documentElement;
  var S = null;                      // настройки от плагина
  var styleEl, bars, sp = {}, lim = {}, timer;
  var st = null, spRaw, lastUpdate = 0, coverUrl = null, volLocalUntil = 0, localVol = -1, localMuted = false;
  var limData = null;
  var seekLocalUntil = 0, seeking = false, seekFrac = 0;
  var webSt = null, qData = null, qEl = null, qKey = null, qAsked = 0;
  var qBusy = null, qBusyTimer = 0, qMsg = null;
  var lyEl = null, lyData = null, lyKey = null, lyIdx = -2, lyUser = 0, lyAsked = 0;   // панель текста песни
  var perfEl = null, perfSheets = -1, perfCount = 0;
  var jrSt = null, jrAsk = 0, jrOn = false, jrObs = null, jrBuf = [], jrFlushAt = 0, jrLastTick = 0, jrMin = null, jrNodes = 0, jrNodesAt = 0;   // журнал лагов
  var regSt = null, regAsk = 0;   // ключи реестра IDE от плагина; таймер подтверждения перезапуска   // режим производительности: свой <style>, сколько листов было при разборе, сколько правил погашено   // переключение на трек из очереди: номер строки, таймер, сообщение
  var nav, navGroup, panel, pg = {}, pageOn = false;

  function svg(d) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + d + '"/></svg>'; }
  function btn(cls, cmd, title, icon) {
    return '<button type="button" class="' + cls + '" data-c="' + cmd + '" title="' + title + '">' + svg(icon) + '</button>';
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mmss(s) { s = Math.max(0, Math.floor(s || 0)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }
  function keyOf(d) { return d ? d.title + '\u0000' + d.artist : null; }
  function setText(node, text) { if (node.textContent !== text) { node.textContent = text; node.title = text; } }
  function opt(m, k) { return !!(S && S[m] && S[m][k]); }
  function num(m, k, def) { var v = S && S[m] ? S[m][k] : undefined; return typeof v === 'number' ? v : def; }
  function mk(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

  // ======================================================================================
  //  Запуск / остановка
  // ======================================================================================

  function start() {
    styleEl = mk('style');
    styleEl.id = 'cct-style';
    styleEl.textContent = CSS;
    document.head.appendChild(styleEl);

    bars = mk('div');
    bars.id = 'cct-bars';
    buildSpotify();
    buildLimits();
    bars.appendChild(sp.el);
    var row = mk('div');
    row.id = 'cct-brow';
    row.appendChild(sp.mini);
    row.appendChild(lim.el);
    bars.appendChild(row);
    document.addEventListener('click', onDocClick, true);
    document.addEventListener('keydown', onKey, true);

    window.__cct = { settings: onSettings, spotify: onSpotify, limits: onLimits, queue: onQueue, lyrics: onLyrics, spotifyWeb: onSpotifyWeb, registry: onRegistry, journal: onJournal };
    window.__cctStop = stop;
    timer = setInterval(tick, 500);
    tick();
    send('ready');
  }

  // Плагин выгружается (обновление без перезапуска IDE): убираем за собой всё, чтобы новая версия встала начисто.
  function stop() {
    clearInterval(timer);
    document.removeEventListener('click', onDocClick, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('pointerup', onPointerUp);
    closeLightbox();
    closeQueue();
    closeLyrics();
    jrStop();
    clearTimeout(jrAsk);
    [bars, styleEl, navGroup, nav, panel, perfEl].forEach(function (e) { if (e) e.remove(); });
    root.classList.remove('cct-sp-on', 'cct-sp-min', 'cct-lim-on', 'cct-page', 'cct-perf-blur');
    delete window.__cct; delete window.__cctStop; delete window.__cctOn; delete window.__cctOut;
  }

  function tick() {
    placeBars();
    spotifyTick();
    if (root.classList.contains('cct-lim-on')) fitLimits();
    settingsTick();
    perfTick();
    journalTick();
  }

  // Ставим бары прямо перед панелью вкладок, без неё — перед полем ввода. React эти узлы
  // перестраивает (смена сессии, настройки), поэтому место проверяется на каждом тике.
  function placeBars() {
    var anchor = document.querySelector('.status-panel') || document.querySelector('.input-area');
    if (!anchor) { if (bars.parentNode) bars.remove(); return; }
    if (bars.nextElementSibling !== anchor || bars.parentNode !== anchor.parentNode) anchor.parentNode.insertBefore(bars, anchor);
    // Края — по соседу: у панели вкладок это margin, у поля ввода — padding.
    var cs = getComputedStyle(anchor), panelAnchor = anchor.classList.contains('status-panel');
    var l = panelAnchor ? cs.marginLeft : cs.paddingLeft, r = panelAnchor ? cs.marginRight : cs.paddingRight;
    if (bars.style.marginLeft !== l) bars.style.marginLeft = l;
    if (bars.style.marginRight !== r) bars.style.marginRight = r;
  }

  function onSettings(s) {
    S = s;
    renderSpotify();
    renderLimits();
    perfTick();
    renderPanel();
  }

  function onDocClick(e) {
    var t = e.target;
    if (!t || !t.closest) return;
    // Клик по родному пункту меню настроек уводит с нашего раздела.
    var item = t.closest('[class*="_sidebarItem_"]');
    if (item && item !== nav && pageOn) setPage(false);
    // Клик мимо панели очереди её закрывает (кнопка очереди переключает сама).
    if (qEl && !t.closest('#cct-queue') && !t.closest('#cct-sp button.cct-q')) closeQueue();
  }

  function onKey(e) {
    if (e.key !== 'Escape') return;
    if (document.getElementById('cct-lightbox')) { e.stopPropagation(); e.preventDefault(); closeLightbox(); }
    else if (qEl) { e.stopPropagation(); e.preventDefault(); closeQueue(); }
  }

  // ======================================================================================
  //  Spotify
  // ======================================================================================

  function buildSpotify() {
    var el = sp.el = mk('div', 'cct-card');
    el.id = 'cct-sp';
    el.innerHTML =
      '<div class="cct-cover">♫</div>' +
      '<div class="cct-meta"><div class="cct-title"></div><div class="cct-sub"></div></div>' +
      '<span class="cct-time"></span>' +
      '<div class="cct-vol">' + btn('cct-mute', 'mute', 'Без звука', ICON.volHi) +
      '<input type="range" min="0" max="100" step="1" value="0" aria-label="Громкость Spotify"></div>' +
      btn('cct-prev', 'prev', 'Предыдущий', ICON.prev) +
      btn('cct-toggle', 'toggle', 'Пауза / воспроизведение', ICON.pause) +
      btn('cct-next', 'next', 'Следующий', ICON.next) +
      btn('cct-ly', 'lyrics', 'Текст песни', ICON.lyrics) +
      btn('cct-q', 'queue', 'Очередь и плейлист', ICON.queue) +
      btn('cct-x', 'hide', 'Свернуть плеер', ICON.x) +
      '<div class="cct-prog"><s></s><i></i></div><span class="cct-seektip"></span>';
    ['cover', 'title', 'sub', 'time', 'toggle', 'vol', 'mute', 'ly', 'q', 'prog', 'seektip'].forEach(function (n) { sp[n] = el.querySelector('.cct-' + n); });
    sp.bar = el.querySelector('.cct-prog i');
    sp.ghost = el.querySelector('.cct-prog s');
    sp.prog.addEventListener('pointerdown', onSeekDown);
    sp.prog.addEventListener('pointermove', onSeekMove);
    sp.prog.addEventListener('pointerup', onSeekUp);
    sp.prog.addEventListener('pointercancel', function () { seeking = false; sp.el.classList.remove('cct-seeking', 'cct-tip'); });
    sp.prog.addEventListener('pointerleave', function () { if (!seeking) sp.el.classList.remove('cct-tip'); });
    sp.range = el.querySelector('.cct-vol input');
    // Свёрнутый плеер — одна кнопка со значком Spotify; по клику плеер разворачивается.
    var mini = sp.mini = mk('button', 'cct-card', svg(ICON.spotify));
    mini.id = 'cct-spm';
    mini.type = 'button';
    mini.addEventListener('click', function () { change('spotify', 'collapsed', false); });
    el.addEventListener('click', onSpotifyClick);
    sp.range.addEventListener('input', function () { setVolume(+sp.range.value); });
    sp.range.addEventListener('pointerdown', function () { sp.vol.classList.add('cct-drag'); });
    window.addEventListener('pointerup', onPointerUp);
    sp.vol.addEventListener('wheel', function (e) {
      if (localVol < 0) return;
      e.preventDefault();
      setVolume(Math.max(0, Math.min(100, localVol + (e.deltaY < 0 ? 5 : -5))));
    }, { passive: false });
  }

  function onPointerUp() { sp.vol.classList.remove('cct-drag'); }

  function onSpotify(d, cover) {
    lastUpdate = Date.now();
    spRaw = d;
    var next = d && d.ok && d.active ? d : null;
    // Сразу после перемотки хелпер ещё может прислать старую позицию — держим свою.
    var keep = next && st && Date.now() < seekLocalUntil && keyOf(next) === keyOf(st) ? position() : null;
    st = next;
    // Часы хелпера и страницы общие, но на всякий случай сдвигаем отметку на разницу.
    if (st && st.now) st.at += Date.now() - st.now;
    if (keep != null) { st.pos = keep; st.at = Date.now(); }
    if (st && Date.now() > volLocalUntil && typeof st.vol === 'number') { localVol = st.vol; localMuted = !!st.muted; }
    if (cover !== undefined) {
      coverUrl = cover || null;
      sp.cover.style.backgroundImage = cover ? 'url("' + cover + '")' : '';
      sp.cover.textContent = cover ? '' : '♫';
      sp.cover.classList.toggle('cct-zoom', !!cover);
      sp.cover.title = cover ? 'Показать обложку' : '';
    }
    renderSpotify();
    renderPanelStatus();
    if (qEl && st && keyOf(st) !== qKey) askQueue();
    if (lyEl && st && keyOf(st) !== lyKey) askLyrics();
  }

  function position() {
    if (!st) return 0;
    var p = st.pos;
    if (st.status === 'Playing' && st.at > 0) p += (Date.now() - st.at) / 1000;
    return st.dur > 0 ? Math.min(p, st.dur) : p;
  }

  function spotifyTick() {
    // Плагин замолчал (выгружен или IDE занята) — прячемся.
    if (st && Date.now() - lastUpdate > 8000) { st = null; spRaw = null; renderSpotify(); }
    if (qEl) queueTick();
    if (lyEl) lyricsTick();
    if (!st) return;
    // JCEF рисует страницу в отдельном процессе и каждый кадр копирует в IDE, поэтому ничего не анимируем
    // непрерывно и трогаем DOM, только когда видимое значение действительно поменялось.
    if (document.hidden || !root.classList.contains('cct-sp-on')) return;
    var p = seeking ? seekFrac * st.dur : position();
    if (opt('spotify', 'time')) setText(sp.time, st.dur > 0 ? mmss(p) + ' / ' + mmss(st.dur) : mmss(p));
    var w = st.dur > 0 ? Math.round(1000 * p / st.dur) / 10 + '%' : '0';
    if (sp.bar.style.width === w) return;
    // Перемотка, новый трек — встаём на место сразу, а не ползём через всю полосу.
    var jump = Math.abs((parseFloat(w) || 0) - (parseFloat(sp.bar.style.width) || 0)) > 2;
    if (jump) sp.bar.style.transition = 'none';
    sp.bar.style.width = w;
    if (jump) { void sp.bar.offsetWidth; sp.bar.style.transition = ''; }
  }

  // ---- перемотка: клик или перетаскивание по нижней полосе ----
  function seekAt(e) {
    var r = sp.prog.getBoundingClientRect();
    seekFrac = r.width > 0 ? Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) : 0;
    var x = Math.max(24, Math.min(r.width - 24, e.clientX - r.left));
    sp.seektip.style.left = x + 'px';
    sp.seektip.textContent = mmss(seekFrac * st.dur) + (seeking ? '' : ' / ' + mmss(st.dur));
    sp.ghost.style.width = (seekFrac * 100).toFixed(2) + '%';
    sp.el.classList.add('cct-tip');
  }
  function onSeekDown(e) {
    if (!st || !(st.dur > 0) || e.button !== 0) return;
    e.preventDefault();
    try { sp.prog.setPointerCapture(e.pointerId); } catch (x) {}
    seeking = true;
    sp.el.classList.add('cct-seeking');
    seekAt(e);
    spotifyTick();
  }
  function onSeekMove(e) {
    if (!st || !(st.dur > 0)) return;
    seekAt(e);
    if (seeking) spotifyTick();
  }
  function onSeekUp(e) {
    if (!seeking || !st) { seeking = false; return; }
    seeking = false;
    seekAt(e);
    sp.el.classList.remove('cct-seeking');
    // Увели курсор далеко от полосы — передумали.
    var r = sp.prog.getBoundingClientRect();
    if (e.clientY < r.top - 40 || e.clientY > r.bottom + 40) { sp.el.classList.remove('cct-tip'); spotifyTick(); return; }
    var target = seekFrac * st.dur;
    st.pos = target; st.at = Date.now();
    seekLocalUntil = Date.now() + 2500;
    send('spotify:seek:' + Math.round(target * 1000));
    spotifyTick();
  }

  function renderSpotify() {
    var live = opt('spotify', 'enabled') && !!st, min = live && opt('spotify', 'collapsed'), on = live && !min;
    root.classList.toggle('cct-sp-on', on);
    root.classList.toggle('cct-sp-min', min);
    if (!on || !queueReady()) closeQueue();
    if (!on || !opt('spotify', 'lyrics')) closeLyrics();
    if (min) {
      var p = st.status !== 'Playing', tip = (p ? 'Spotify на паузе: ' : 'Spotify: ') + [st.title, st.artist].filter(Boolean).join(' — ') + '\nРазвернуть плеер';
      sp.mini.classList.toggle('cct-paused', p);
      if (sp.mini.title !== tip) { sp.mini.title = tip; sp.mini.setAttribute('aria-label', tip); }
    }
    if (!on) return;
    var el = sp.el, paused = st.status !== 'Playing';
    el.classList.toggle('cct-paused', paused);
    el.classList.toggle('cct-lg', num('spotify', 'size', 1) === 2);
    el.classList.toggle('cct-notime', !opt('spotify', 'time'));
    el.classList.toggle('cct-novol', !opt('spotify', 'volume') || localVol < 0);
    el.classList.toggle('cct-noq', !queueReady());
    el.classList.toggle('cct-noly', !opt('spotify', 'lyrics'));
    el.classList.toggle('cct-nodur', !(st.dur > 0));
    setText(sp.title, st.title);
    setText(sp.sub, [st.artist, st.album].filter(Boolean).join(' · '));
    var icon = paused ? ICON.play : ICON.pause;
    if (sp.toggle.getAttribute('data-icon') !== icon) { sp.toggle.setAttribute('data-icon', icon); sp.toggle.innerHTML = svg(icon); }
    renderVolume();
    spotifyTick();
  }

  function renderVolume() {
    if (localVol < 0) return;
    var muted = localMuted || localVol === 0;
    var icon = muted ? ICON.volOff : localVol < 50 ? ICON.volLo : ICON.volHi;
    if (sp.mute.getAttribute('data-icon') !== icon) { sp.mute.setAttribute('data-icon', icon); sp.mute.innerHTML = svg(icon); }
    sp.mute.title = localMuted ? 'Включить звук' : 'Без звука';
    sp.vol.classList.toggle('cct-muted', localMuted);
    if (document.activeElement !== sp.range || Date.now() > volLocalUntil) sp.range.value = localVol;
    sp.range.style.setProperty('--p', localVol + '%');
    sp.vol.title = 'Громкость Spotify: ' + localVol + '%' + (localMuted ? ' (без звука)' : '') + ' — колесо мыши ±5';
  }

  function setVolume(v) {
    localVol = v;
    localMuted = false;            // хелпер при смене громкости снимает «без звука»
    volLocalUntil = Date.now() + 2000;
    renderVolume();
    send('spotify:vol:' + v);
  }

  function onSpotifyClick(e) {
    if (e.target.closest('.cct-cover')) { if (coverUrl) openLightbox(); return; }
    var b = e.target.closest && e.target.closest('button[data-c]');
    if (!b) return;
    var c = b.getAttribute('data-c');
    if (c === 'hide') { change('spotify', 'collapsed', true); return; }
    if (c === 'queue') { if (qEl) closeQueue(); else openQueue(); return; }
    if (c === 'lyrics') { if (lyEl) closeLyrics(); else openLyrics(); return; }
    if (c === 'mute') {
      localMuted = !localMuted;
      volLocalUntil = Date.now() + 2000;
      renderVolume();
      send('spotify:mute:' + (localMuted ? 1 : 0));
      return;
    }
    if (c === 'toggle' && st) {
      // Мгновенный отклик, настоящее состояние придёт со следующим обновлением.
      st.pos = position(); st.at = Date.now();
      st.status = st.status === 'Playing' ? 'Paused' : 'Playing';
      renderSpotify();
    }
    send('spotify:' + c);
  }

  function openLightbox() {
    closeLightbox();
    var box = mk('div', null,
      '<img alt="">' +
      (st ? '<div class="cct-cap"><b>' + esc(st.title) + '</b><span>' + esc([st.artist, st.album].filter(Boolean).join(' · ')) + '</span></div>' : ''));
    box.id = 'cct-lightbox';
    box.title = 'Закрыть (Esc)';
    box.querySelector('img').src = coverUrl;
    box.addEventListener('click', closeLightbox);
    document.body.appendChild(box);
  }

  function closeLightbox() {
    var box = document.getElementById('cct-lightbox');
    if (box) box.remove();
  }

  // ======================================================================================
  //  Очередь и плейлист (Spotify Web API через плагин)
  // ======================================================================================

  var CTX = { playlist: 'Плейлист', album: 'Альбом', artist: 'Исполнитель', show: 'Подкаст', collection: 'Ваша медиатека' };
  var CTX_NONAME = { playlist: 'Подборка Spotify', album: 'Альбом', artist: 'Исполнитель', show: 'Подкаст', collection: 'Любимые треки' };
  var Q_ERR = {
    noclient: 'Очередь и плейлист Spotify отдаёт только через Web API. Подключите его: <b>Настройки → CC GUI Tweaks → Spotify</b>.',
    nologin: 'Spotify не подключён. Нажмите «Подключить» в <b>Настройки → CC GUI Tweaks → Spotify</b>.',
    auth: 'Вход в Spotify устарел — подключитесь заново в <b>Настройки → CC GUI Tweaks → Spotify</b>.',
    forbidden: 'Spotify отказал в доступе (403). Проверьте, что ваш аккаунт добавлен в <b>User Management</b> приложения на developer.spotify.com.',
    nodevice: 'Сейчас ничего не играет.',
    ratelimit: 'Spotify просит подождать: слишком много запросов. Обновлю чуть позже.',
    network: 'Нет связи с api.spotify.com.'
  };
  /** Почему нельзя включить трек кликом (поле control из очереди). */
  var Q_CTL = {
    shuffle: 'Включено перемешивание — порядок выбирает Spotify, поэтому переключаться по очереди отсюда нельзя.',
    scope: 'Чтобы включать треки отсюда, переподключите Spotify в настройках — нужно разрешение на управление.',
    restricted: 'Это устройство Spotify не даёт управлять воспроизведением.'
  };
  /** Ошибки переключения (поле played). */
  var Q_PLAY = {
    premium: 'Включать треки через Web API Spotify разрешает только с подпиской Premium.',
    shuffle: Q_CTL.shuffle,
    scope: Q_CTL.scope,
    stale: 'Очередь успела измениться — обновил список, попробуйте ещё раз.',
    nodevice: 'Spotify не нашёл активное устройство — включите воспроизведение в приложении.',
    forbidden: 'Spotify отказал в управлении (403).',
    ratelimit: 'Spotify просит подождать — слишком много запросов.',
    auth: 'Вход в Spotify устарел — подключитесь заново в настройках.',
    network: 'Нет связи с api.spotify.com.'
  };

  function openQueue() {
    closeQueue();
    closeLyrics();
    qEl = mk('div', null,
      '<div class="cct-qh"><b>Очередь Spotify</b>' +
      '<button type="button" data-q="refresh" title="Обновить"><span class="codicon codicon-refresh"></span></button>' +
      '<button type="button" data-q="close" title="Закрыть (Esc)"><span class="codicon codicon-close"></span></button></div>' +
      '<div class="cct-qb"></div>');
    qEl.id = 'cct-queue';
    qEl.setAttribute('role', 'dialog');
    qEl.setAttribute('aria-label', 'Очередь Spotify');
    qEl.addEventListener('click', onQueueClick);
    document.body.appendChild(qEl);
    sp.q.classList.add('cct-on');
    placeQueue();
    renderQueue();
    askQueue();
  }

  // ---- текст песни: плагин ищет его на lrclib.net, синхронный текст идёт за треком ----
  function openLyrics() {
    closeLyrics();
    closeQueue();
    lyEl = mk('div', null,
      '<div class="cct-qh"><b>Текст песни</b>' +
      '<button type="button" data-y="close" title="Закрыть"><span class="codicon codicon-close"></span></button></div>' +
      '<div class="cct-yb"></div><div class="cct-yf"></div>');
    lyEl.id = 'cct-lyrics';
    lyEl.setAttribute('role', 'dialog');
    lyEl.setAttribute('aria-label', 'Текст песни');
    lyEl.addEventListener('click', onLyricsClick);
    var body = lyEl.querySelector('.cct-yb');
    body.addEventListener('wheel', lyricsManual, { passive: true });
    body.addEventListener('pointerdown', lyricsManual);
    document.body.appendChild(lyEl);
    sp.ly.classList.add('cct-on');
    placePop(lyEl, 380);
    askLyrics();
  }

  // Пользователь крутит текст сам — несколько секунд не мешаем автопрокруткой.
  function lyricsManual() { lyUser = Date.now(); }

  function closeLyrics() {
    if (!lyEl) return;
    lyEl.remove();
    lyEl = null;
    if (sp.ly) sp.ly.classList.remove('cct-on');
  }

  function askLyrics() {
    if (!st) return;
    lyKey = keyOf(st);
    lyData = null;
    lyIdx = -2;
    lyUser = 0;
    lyAsked = Date.now();
    renderLyrics();
    send('lyrics:' + JSON.stringify({ t: st.title || '', a: st.artist || '', al: st.album || '', d: st.dur > 0 ? Math.round(st.dur) : 0 }));
  }

  function onLyrics(d) {
    // ответ на старый трек (переключили, пока искали) не нужен
    if (!lyEl || !d || !st || d.t !== (st.title || '') || d.a !== (st.artist || '')) return;
    lyData = d;
    lyIdx = -2;
    renderLyrics();
  }

  function renderLyrics() {
    if (!lyEl) return;
    var head = lyEl.querySelector('.cct-qh b'), body = lyEl.querySelector('.cct-yb'), d = lyData;
    var name = st ? [st.title, st.artist].filter(Boolean).join(' — ') : '';
    head.textContent = name ? 'Текст · ' + name : 'Текст песни';
    head.title = name;
    var synced = !!(d && d.synced && d.synced.length);
    body.classList.toggle('cct-plain', !!(d && d.plain && !synced));
    lyEl.querySelector('.cct-yf').textContent = 'Текст: LRCLIB' + (synced ? ' · клик по строке — перейти к ней' : '');
    body.scrollTop = 0;
    if (!d) {
      body.innerHTML = '<div class="cct-ye"><span class="codicon codicon-loading codicon-modifier-spin"></span>Ищу текст…</div>';
    } else if (d.err) {
      body.innerHTML = '<div class="cct-ye">Не удалось получить текст' +
        (d.err === 'net' || d.err === 'timeout' ? ': нет связи с lrclib.net.' : ' (' + esc(d.err) + ').') +
        '<br><button type="button" class="cct-yr" data-y="retry">Повторить</button></div>';
    } else if (d.inst) {
      body.innerHTML = '<div class="cct-ye">♪ Инструментальный трек — слов нет.</div>';
    } else if (synced) {
      body.innerHTML = d.synced.map(function (l, i) {
        return '<div class="cct-yl" data-i="' + i + '" title="' + mmss(l[0] / 1000) + '">' + (l[1] ? esc(l[1]) : '♪') + '</div>';
      }).join('');
      lyricsTick(true);
    } else if (d.plain) {
      body.innerHTML = '<div class="cct-ye cct-yn">Для этого трека есть только текст без синхронизации.</div>' +
        String(d.plain).split(/\r?\n/).map(function (l) { return '<div class="cct-yl">' + esc(l) + '</div>'; }).join('');
    } else {
      body.innerHTML = '<div class="cct-ye">Текст для этого трека не найден.</div>';
    }
  }

  // Подсветка текущей строки. DOM трогаем, только когда строка сменилась.
  function lyricsTick(instant) {
    if (!placePop(lyEl, 380)) { closeLyrics(); return; }
    if (!lyData && Date.now() - lyAsked > 15000) { lyData = { err: 'timeout' }; renderLyrics(); return; }
    var d = lyData;
    if (!d || !d.synced || !st || document.hidden) return;
    var ms = (seeking ? seekFrac * st.dur : position()) * 1000 + 250, i = -1;
    for (var k = 0; k < d.synced.length && d.synced[k][0] <= ms; k++) i = k;
    if (i === lyIdx) return;
    lyIdx = i;
    var body = lyEl.querySelector('.cct-yb'), rows = body.children;
    for (var j = 0; j < rows.length; j++) {
      rows[j].classList.toggle('cct-past', j < i);
      rows[j].classList.toggle('cct-cur', j === i);
    }
    if (instant !== true && Date.now() - lyUser < 5000) return;
    var row = rows[Math.max(0, i)];
    if (row) body.scrollTo({ top: Math.max(0, row.offsetTop - body.clientHeight * 0.35), behavior: instant === true ? 'auto' : 'smooth' });
  }

  function onLyricsClick(e) {
    var b = e.target.closest('[data-y]');
    if (b) { if (b.getAttribute('data-y') === 'close') closeLyrics(); else askLyrics(); return; }
    var row = e.target.closest('.cct-yl[data-i]');
    var l = row && lyData && lyData.synced && lyData.synced[+row.getAttribute('data-i')];
    if (!l || !st || !(st.dur > 0)) return;
    st.pos = l[0] / 1000;
    st.at = Date.now();
    seekLocalUntil = Date.now() + 2500;
    lyUser = 0;
    send('spotify:seek:' + Math.round(l[0]));
    spotifyTick();
  }

  function closeQueue() {
    if (!qEl) return;
    qEl.remove();
    qEl = null;
    qMsg = null;
    if (sp.q) sp.q.classList.remove('cct-on');
  }

  function askQueue() {
    qKey = keyOf(st);
    qAsked = Date.now();
    send('spweb:queue');
  }

  // Пока панель открыта — держим её над карточкой и раз в 20 с обновляем.
  function queueTick() {
    placeQueue();
    if (qEl && Date.now() - qAsked > 20000) askQueue();
  }

  function placeQueue() {
    if (qEl && !placePop(qEl, 440)) closeQueue();
  }

  // Всплывающая панель над карточкой плеера, по правому краю. false — плеера не видно.
  function placePop(pop, maxH) {
    var r = sp.el.getBoundingClientRect();
    if (!r.width) return false;
    var w = Math.min(r.width, 460);
    pop.style.left = Math.round(r.right - w) + 'px';
    pop.style.width = Math.round(w) + 'px';
    pop.style.bottom = Math.round(window.innerHeight - r.top + 6) + 'px';
    pop.style.maxHeight = Math.round(Math.max(140, Math.min(maxH, r.top - 14))) + 'px';
    return true;
  }

  function onQueueClick(e) {
    var a = e.target.closest('[data-uri]');
    if (a) { send('spweb:open:' + a.getAttribute('data-uri')); return; }
    var row = e.target.closest('.cct-qp[data-i]');
    if (row) { playFromQueue(+row.getAttribute('data-i')); return; }
    var b = e.target.closest('button[data-q]');
    if (!b) return;
    if (b.getAttribute('data-q') === 'close') closeQueue();
    else askQueue();
  }

  function playFromQueue(i) {
    var t = qData && qData.items && qData.items[i];
    if (!t || !t.uri || qBusy !== null || !qData || qData.control !== 'ok') return;
    qBusy = i;
    qMsg = null;
    clearTimeout(qBusyTimer);
    // Плагин ответит очередью с полем played; если ответа нет — снимаем индикатор сами.
    qBusyTimer = setTimeout(function () { qBusy = null; renderQueue(); }, 20000);
    send('spweb:play:' + i + ':' + t.uri);
    renderQueue();
  }

  function onQueue(d) {
    qData = d;
    if (d && d.played) {
      clearTimeout(qBusyTimer);
      qBusy = null;
      qMsg = d.played === 'ok' ? null : (Q_PLAY[d.played] || 'Не удалось переключить трек: ' + esc(d.played));
    } else if (d && d.ok && qMsg && qBusy === null && !/premium/.test(qMsg)) {
      qMsg = null;
    }
    renderQueue();
  }

  /** Кнопка очереди — только если опция включена и Spotify Web API подключён. */
  function queueReady() {
    return opt('spotify', 'queue') && !!(webSt && webSt.connected);
  }

  function onSpotifyWeb(w) {
    var was = !!(webSt && webSt.connected);
    webSt = w;
    if (st) renderSpotify();
    if (qEl && w && w.connected && !was) askQueue();
    renderQueue();
    renderWeb();
  }

  function qImg(url, fallback) {
    return '<span class="cct-qi"' + (url ? ' style="background-image:url(&quot;' + esc(url) + '&quot;)"' : '') + '>' + (url ? '' : fallback) + '</span>';
  }

  function qRow(t, now, i, playable) {
    var busy = i !== undefined && i === qBusy;
    var cls = 'cct-qr' + (now ? ' cct-now' : '') + (playable ? ' cct-qp' : '') + (busy ? ' cct-qbusy' : '');
    var over = playable || busy ? '<span class="cct-qpi">' + (busy ? '<span class="codicon codicon-loading codicon-modifier-spin"></span>' : svg(ICON.play)) + '</span>' : '';
    var img = qImg(t.img, '♫').replace(/<\/span>$/, over + '</span>');
    return '<div class="' + cls + '"' + (playable ? ' data-i="' + i + '" title="Включить «' + esc(t.name) + '»"' : '') + '>' + img +
      '<div class="cct-qm"><div class="cct-qn" title="' + esc(t.name) + '">' + esc(t.name) + '</div>' +
      '<div class="cct-qa" title="' + esc(t.artists) + '">' + esc(t.artists) + '</div></div>' +
      (t.dur > 0 ? '<span class="cct-qd">' + mmss(t.dur) + '</span>' : '') + '</div>';
  }

  function renderQueue() {
    if (!qEl) return;
    var body = qEl.querySelector('.cct-qb'), d = qData, w = webSt, html;
    if (w && !w.connected) html = '<div class="cct-qe">' + Q_ERR[w.client ? 'nologin' : 'noclient'] + '</div>';
    else if (!d) html = '<div class="cct-qe">Загружаем очередь…</div>';
    else if (d.error) html = '<div class="cct-qe">' + (Q_ERR[d.error] || 'Не удалось получить очередь: ' + esc(d.error)) + '</div>';
    else {
      html = '';
      var c = d.context;
      if (c && c.type) {
        var name = c.name || CTX_NONAME[c.type] || 'Источник';
        var head = (CTX[c.type] || 'Играет из') + (c.owner ? ' · ' + c.owner : '');
        html += '<div class="cct-qr cct-qc">' + qImg(c.img, '<span class="codicon codicon-list-unordered"></span>') +
          '<div class="cct-qm"><div class="cct-qa">' + esc(head) + '</div>' +
          (c.uri ? '<a class="cct-qn" data-uri="' + esc(c.uri) + '" title="Открыть в Spotify">' + esc(name) + '</a>' : '<div class="cct-qn">' + esc(name) + '</div>') +
          '</div></div>';
      }
      if (qMsg) html = '<div class="cct-qw">' + qMsg + '</div>' + html;
      if (d.now) html += '<div class="cct-qs">Сейчас играет</div>' + qRow(d.now, true);
      var items = d.items || [], can = d.control === 'ok';
      html += '<div class="cct-qs">Далее' + (items.length ? ' · ' + items.length : '') + '</div>' +
        (items.length && !can && Q_CTL[d.control] ? '<div class="cct-qnote">' + Q_CTL[d.control] + '</div>' : '') +
        (items.length ? items.map(function (t, i) { return qRow(t, false, i, can && !!t.uri); }).join('') : '<div class="cct-qe">Очередь пуста</div>');
    }
    body.classList.toggle('cct-busy', qBusy !== null);
    if (body.getAttribute('data-h') !== html) { body.innerHTML = html; body.setAttribute('data-h', html); }
  }

  // ======================================================================================
  //  Лимиты Claude
  // ======================================================================================

  function buildLimits() {
    var el = lim.el = mk('div', 'cct-card');
    el.id = 'cct-lim';
    el.addEventListener('click', limRefresh);
  }

  // Обновить по клику. Индикатор держится до ответа плагина (он может отложить запрос до конца паузы).
  function limRefresh() {
    if (lim.busy) return;
    var wait = limData && limData.retry ? Math.max(0, limData.retry - Date.now()) : 0;
    lim.busy = true;
    clearTimeout(lim.busyT);
    lim.busyT = setTimeout(function () { lim.busy = false; renderLimits(); }, wait + 30000);
    send('limits:refresh');
    renderLimits();
  }

  function onLimits(d) {
    clearTimeout(lim.busyT);
    lim.busy = false;
    limData = d;
    renderLimits();
    renderPanelStatus();
  }

  var DAYS = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
  function hhmm(t) { return String(t.getHours()).padStart(2, '0') + ':' + String(t.getMinutes()).padStart(2, '0'); }
  function resetShort(iso) {
    var t = new Date(iso);
    if (isNaN(t)) return '';
    return t - Date.now() < 20 * 3600e3 ? hhmm(t) : DAYS[t.getDay()] + ' ' + hhmm(t);
  }
  function resetLong(iso) {
    var t = new Date(iso);
    if (isNaN(t)) return '';
    var left = Math.max(0, t - Date.now()), h = Math.floor(left / 3600e3), m = Math.round((left % 3600e3) / 60e3);
    var when = t.toLocaleString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
    return 'сброс ' + when + ' (через ' + (h >= 24 ? Math.floor(h / 24) + ' д ' + (h % 24) + ' ч' : h ? h + ' ч ' + m + ' мин' : m + ' мин') + ')';
  }

  // Из ответа /api/oauth/usage: сначала новый список limits[], при его отсутствии — five_hour / seven_day.
  function limitItems(d) {
    var out = [], five = false;
    (d.limits || []).forEach(function (l) {
      var pct = l.percent != null ? l.percent : l.utilization;
      if (pct == null) return;
      var k = (l.kind || '') + ' ' + (l.group || '');
      var model = l.scope && l.scope.model, item = { pct: +pct, sev: l.severity, resets: l.resets_at, order: 2 };
      if (/five|5h|session|hour/i.test(k)) { item.label = '5 ч'; item.full = '5-часовое окно'; item.order = 0; five = true; }
      else if (model) {
        if (!opt('limits', 'models')) return;
        item.label = model.display_name || model.name || 'Модель';
        item.full = 'Недельный лимит ' + item.label;
      } else if (/week/i.test(k)) { item.label = 'Неделя'; item.full = 'Недельный лимит (все модели)'; item.order = 1; }
      else { item.label = l.kind || '?'; item.full = item.label; }
      out.push(item);
    });
    if (!five && d.five_hour && d.five_hour.utilization != null)
      out.push({ label: '5 ч', full: '5-часовое окно', pct: +d.five_hour.utilization, resets: d.five_hour.resets_at, order: 0 });
    if (!out.some(function (i) { return i.order === 1; }) && d.seven_day && d.seven_day.utilization != null)
      out.push({ label: 'Неделя', full: 'Недельный лимит (все модели)', pct: +d.seven_day.utilization, resets: d.seven_day.resets_at, order: 1 });
    return out.sort(function (a, b) { return a.order - b.order; });
  }

  var LIMIT_ERR = {
    nologin: 'нет входа Claude Code',
    auth: 'вход Claude Code устарел — выполните /login',
    network: 'нет связи с api.anthropic.com',
    'http 429': 'Anthropic просит реже обновлять лимиты (429)'
  };
  function limitError(e) {
    if (LIMIT_ERR[e]) return LIMIT_ERR[e];
    if (/^http 5/.test(e || '')) return 'сервер Anthropic недоступен (' + e.slice(5) + ')';
    return e ? 'ошибка: ' + e : '';
  }
  /** Когда можно повторить, если сервер просил подождать. */
  function retryNote(d) {
    return d && d.retry && d.retry > Date.now() ? ', повтор после ' + hhmm(new Date(d.retry)) : '';
  }
  /** Кнопка «Обновить» при ошибке: с подписью, а рядом со старыми цифрами — только значок. */
  function limButton(d, iconOnly) {
    var busy = !!lim.busy;
    var label = busy ? (d.retry && d.retry > Date.now() ? 'Ждём ' + hhmm(new Date(d.retry)) : 'Обновляю…') : 'Обновить';
    return '<button type="button" class="cct-lref' + (iconOnly ? ' cct-ic' : '') + '"' + (busy ? ' disabled' : '') +
      ' title="' + esc(busy ? label : 'Обновить лимиты' + retryNote(d)) + '">' +
      '<span class="codicon ' + (busy ? 'codicon-loading codicon-modifier-spin' : 'codicon-refresh') + '"></span>' +
      (iconOnly ? '' : '<span>' + label + '</span>') + '</button>';
  }

  function renderLimits() {
    var d = limData, on = opt('limits', 'enabled') && !!d;
    root.classList.toggle('cct-lim-on', on);
    if (!on) return;
    var el = lim.el, items = limitItems(d);
    el.classList.toggle('cct-stale', !!d.stale);
    if (el.classList.contains('cct-noreset') === opt('limits', 'resets')) { el.classList.toggle('cct-noreset'); lim.fitW = -1; }
    var html = '<span class="codicon codicon-dashboard"></span>';
    if (!items.length) {
      html += '<span class="cct-msg">Лимиты Claude: ' + esc(d.error ? limitError(d.error) + retryNote(d) : 'данных пока нет') + '</span>';
      if (d.error) html += limButton(d, false);
    } else {
      items.forEach(function (i) {
        var p = Math.max(0, Math.min(100, Math.round(i.pct)));
        var cls = p >= 90 || /crit|exceed|block/i.test(i.sev || '') ? ' cct-crit' : p >= 75 || /warn|high/i.test(i.sev || '') ? ' cct-warn' : '';
        var tip = i.full + ': ' + p + '%' + (i.resets ? ', ' + resetLong(i.resets) : '');
        html += '<div class="cct-li' + cls + '" title="' + esc(tip) + '"><span class="cct-ll">' + esc(i.label) + '</span>' +
          '<span class="cct-lm"><i style="width:' + p + '%"></i></span><span class="cct-lp">' + p + '%</span>' +
          (i.resets ? '<span class="cct-lr">' + esc(resetShort(i.resets)) + '</span>' : '') + '</div>';
      });
      if (d.stale) html += limButton(d, true);
    }
    if (el.getAttribute('data-h') !== html) { el.innerHTML = html; el.setAttribute('data-h', html); lim.fitW = -1; }
    fitLimits();
    el.title = d.stale ? 'Данные устарели: ' + limitError(d.error) + retryNote(d) + '. Клик — обновить' : 'Клик — обновить';
  }

  // Узко — сначала убираем время сброса (оно есть в подсказке), чтобы не резались подписи и шкалы.
  function fitLimits() {
    var el = lim.el, w = el.clientWidth;
    if (!w || w === lim.fitW) return;
    lim.fitW = w;
    el.classList.remove('cct-tight');
    if (el.classList.contains('cct-noreset')) return;
    var tight = [].some.call(el.querySelectorAll('.cct-li'), function (li) {
      var label = li.querySelector('.cct-ll'), meter = li.querySelector('.cct-lm');
      return label.scrollWidth > label.clientWidth || meter.offsetWidth < 28;
    });
    el.classList.toggle('cct-tight', tight);
  }

  // ======================================================================================
  //  Раздел «CC GUI Tweaks» в настройках CC GUI
  // ======================================================================================

  function tokenLike(sample, prefix) {
    var cls = sample && typeof sample.className === 'string' ? sample.className.split(/\s+/) : [];
    for (var i = 0; i < cls.length; i++) if (cls[i].indexOf(prefix) === 0) return cls[i];
    return '';
  }

  function settingsTick() {
    var items = document.querySelector('[class*="_sidebarItems_"]');
    var content = document.querySelector('[class*="_settingsContent_"]:not(#cct-panel)');
    if (!items || !content) {
      if (nav) { (navGroup || nav).remove(); nav = navGroup = null; }
      if (panel && panel.parentNode) panel.remove();
      if (pageOn) setPage(false);
      return;
    }
    if (!nav) buildNav(items);
    var host = navGroup || nav;
    if (host.parentNode !== items || items.lastElementChild !== host) items.appendChild(host);
    if (!panel) buildPanel();
    var cls = tokenLike(content, '_settingsContent_');
    if (panel.className !== cls) panel.className = cls;
    if (panel.parentNode !== content.parentNode || panel.previousElementSibling !== content) content.parentNode.insertBefore(panel, content.nextSibling);
    // Свёрнутое меню прячет подписи — тогда подсказка с названием.
    var sidebar = items.closest('[class*="_sidebar_"]');
    var tip = sidebar && /_collapsed_/.test(sidebar.className) ? 'CC GUI Tweaks' : '';
    if (nav.title !== tip) nav.title = tip;
  }

  function buildNav(items) {
    var sampleItem = items.querySelector('[class*="_sidebarItem_"]');
    var sampleText = items.querySelector('[class*="_sidebarItemText_"]');
    var sampleGroup = items.querySelector('[class*="_sidebarGroup_"]');
    nav = mk('div', (tokenLike(sampleItem, '_sidebarItem_') + ' cct-nav').trim(),
      '<span class="codicon codicon-extensions"></span><span class="' + tokenLike(sampleText, '_sidebarItemText_') + '">CC GUI Tweaks</span>');
    nav.setAttribute('role', 'button');
    nav.setAttribute('tabindex', '0');
    nav.setAttribute('aria-label', 'CC GUI Tweaks');
    nav.addEventListener('click', function () { setPage(true); });
    nav.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setPage(true); } });
    navGroup = null;
    if (sampleGroup) {
      navGroup = mk('div', tokenLike(sampleGroup, '_sidebarGroup_'));
      navGroup.id = 'cct-nav-group';
      navGroup.appendChild(nav);
    }
    nav.classList.toggle('cct-nav-on', pageOn);
  }

  function setPage(on) {
    pageOn = on;
    root.classList.toggle('cct-page', on);
    if (nav) nav.classList.toggle('cct-nav-on', on);
    if (on && panel) { panel.scrollTop = 0; renderPanel(); }
  }

  // ======================================================================================
  //  Производительность
  // ======================================================================================
  // Чат рисуется встроенным Chromium вне экрана, и каждый кадр копируется в окно IDE. Бесконечные
  // CSS-анимации CC GUI (спиннеры, пульсации, питомец) заставляют рисовать кадры без остановки.
  // Правила с infinite ищутся в самих стилях страницы — так список не устаревает с обновлениями CC GUI.

  function perfTick() {
    var on = opt('perf', 'enabled');
    var blur = on && opt('perf', 'blur');
    if (root.classList.contains('cct-perf-blur') !== blur) root.classList.toggle('cct-perf-blur', blur);
    if (!(on && opt('perf', 'anim'))) {
      if (perfEl) { perfEl.remove(); perfEl = null; perfSheets = -1; perfCount = 0; }
      return;
    }
    if (perfEl && perfEl.isConnected && document.styleSheets.length === perfSheets) return;
    if (!perfEl) { perfEl = mk('style'); perfEl.id = 'cct-perf'; }
    var sels = perfScan();
    perfCount = sels.length;
    perfEl.textContent = sels.map(function (x) { return x + '{animation:none!important}'; }).join('\n');
    if (!perfEl.isConnected) document.head.appendChild(perfEl);
    perfSheets = document.styleSheets.length;
    renderPanelStatus();
  }

  function perfScan() {
    var out = [], seen = {};
    function walk(rules) {
      for (var i = 0; i < rules.length; i++) {
        var r = rules[i], st = r.style;
        if (r.selectorText && st && !seen[r.selectorText] &&
          (/infinite/.test(st.animationIterationCount || '') || /infinite/.test(st.animation || ''))) {
          seen[r.selectorText] = 1;
          out.push(r.selectorText);
        }
        if (r.cssRules && r.cssRules.length) walk(r.cssRules);   // @media, @supports, @layer, вложенные правила
      }
    }
    for (var i = 0; i < document.styleSheets.length; i++) {
      var sh = document.styleSheets[i];
      if (sh.ownerNode && sh.ownerNode.id === 'cct-perf') continue;
      try { walk(sh.cssRules); } catch (e) { /* чужой домен — не читается */ }
    }
    return out;
  }

  // ---------- журнал лагов ----------
  // Long Animation Frames (Chromium 123+) говорят, сколько длился кадр и какие скрипты в нём работали;
  // в старом Chromium — хотя бы longtask. Подробно пишутся кадры от 100 мс (не больше 30 в минуту),
  // остальное от 50 мс — счётчиком в минутной сводке. Пачки уходят плагину раз в 5 секунд.

  function journalTick() {
    var on = opt('perf', 'journal');
    if (on !== jrOn) { if (on) jrStart(); else jrStop(); }
    if (!on) return;
    var now = performance.now();
    // таймер тикает раз в 500 мс: пропуск больше 2 с — страница стояла (даже если кадр не попал в наблюдатель)
    if (jrLastTick && !document.hidden && now - jrLastTick > 2000) jrPush({ k: 'stall', d: Math.round(now - jrLastTick - 500), n: nodeCount() });
    jrLastTick = document.hidden ? 0 : now;
    if (now - jrMin.at >= 60000) {
      var o = { k: 'page', lf: jrMin.lf, lfms: Math.round(jrMin.lfms), max: Math.round(jrMin.max), n: nodeCount(), vis: !document.hidden };
      if (performance.memory) o.mem = Math.round(performance.memory.usedJSHeapSize / 1048576);
      jrPush(o);
      jrMin = { at: now, lf: 0, lfms: 0, max: 0, rec: 0 };
    }
    if (jrBuf.length && now - jrFlushAt >= 5000) jrFlush();
  }

  function jrType() {
    var t = (window.PerformanceObserver && PerformanceObserver.supportedEntryTypes) || [];
    return t.indexOf('long-animation-frame') >= 0 ? 'long-animation-frame' : t.indexOf('longtask') >= 0 ? 'longtask' : '';
  }

  function jrStart() {
    jrOn = true;
    jrBuf = [];
    jrLastTick = 0;
    jrFlushAt = performance.now();
    jrMin = { at: jrFlushAt, lf: 0, lfms: 0, max: 0, rec: 0 };
    var type = jrType();
    if (!type) return;
    try {
      jrObs = new PerformanceObserver(function (list) { list.getEntries().forEach(jrEntry); });
      jrObs.observe({ type: type, buffered: false });
    } catch (e) { jrObs = null; }
  }

  function jrStop() {
    jrOn = false;
    if (jrObs) jrObs.disconnect();
    jrObs = null;
    jrBuf = [];
  }

  function jrEntry(e) {
    var d = e.duration;
    if (d < 50 || !jrMin) return;
    jrMin.lf++;
    jrMin.lfms += d;
    if (d > jrMin.max) jrMin.max = d;
    if (d < 100 || ++jrMin.rec > 30) return;
    var end = e.startTime + d, o = { k: 'frame', t: Math.round(performance.timeOrigin + e.startTime), d: Math.round(d), n: nodeCount(), vis: !document.hidden };
    if (e.blockingDuration != null) o.b = Math.round(e.blockingDuration);
    if (e.styleAndLayoutStart) o.sl = Math.round(end - e.styleAndLayoutStart);
    if (e.renderStart) o.r = Math.round(end - e.renderStart);
    if (e.scripts && e.scripts.length) {
      o.s = Array.prototype.slice.call(e.scripts).sort(function (a, b) { return b.duration - a.duration; }).slice(0, 3).map(function (x) {
        return { d: Math.round(x.duration), i: ((x.invokerType || '') + ' ' + String(x.invoker || '').slice(0, 80)).trim(),
          fn: x.sourceFunctionName || '', f: shortUrl(x.sourceURL), c: x.sourceCharPosition, l: Math.round(x.forcedStyleAndLayoutDuration || 0) };
      });
    }
    jrPush(o);
  }

  function shortUrl(u) {
    u = String(u || '').split(/[?#]/)[0];
    if (!u) return '';
    var name = u.slice(u.lastIndexOf('/') + 1);
    return /^cc-gui-tweaks:/.test(u) ? 'cc-gui-tweaks/' + name : name;
  }

  // Число узлов страницы — признак разросшегося чата; считается не чаще раза в 10 секунд.
  function nodeCount() {
    var now = Date.now();
    if (now - jrNodesAt > 10000) { jrNodes = document.getElementsByTagName('*').length; jrNodesAt = now; }
    return jrNodes;
  }

  function jrPush(o) {
    if (!o.t) o.t = Date.now();
    jrBuf.push(o);
    if (jrBuf.length > 200) jrBuf.shift();
  }

  function jrFlush() {
    jrFlushAt = performance.now();
    while (jrBuf.length) send('perflog:' + JSON.stringify(jrBuf.splice(0, 50)));
  }

  function onJournal(d) {
    jrSt = d;
    renderJournal();
  }

  function onJournalClick(e) {
    var b = e.target.closest('[data-j]');
    if (!b || b.disabled) return;
    var act = b.getAttribute('data-j');
    if (act === 'export') {
      if (jrBuf.length) jrFlush();          // последние события — в файл до сборки архива
      var meta = { ua: navigator.userAgent, dpr: window.devicePixelRatio, w: innerWidth, h: innerHeight, nodes: document.getElementsByTagName('*').length, observer: jrType() || 'нет' };
      if (performance.memory) meta.memMb = Math.round(performance.memory.usedJSHeapSize / 1048576);
      send('journal:export:' + JSON.stringify(meta));
      jrSt = Object.assign({}, jrSt, { busy: true, err: null, saved: null });
    } else if (act === 'clear') {
      if (!jrAsk) { jrAsk = setTimeout(function () { jrAsk = 0; renderJournal(); }, 5000); renderJournal(); return; }
      clearTimeout(jrAsk);
      jrAsk = 0;
      jrBuf = [];
      send('journal:clear');
    } else {
      send('journal:reveal');
    }
    renderJournal();
  }

  function kb(n) {
    return n >= 1048576 ? (n / 1048576).toFixed(1).replace('.', ',') + ' МБ' : Math.max(1, Math.round(n / 1024)) + ' КБ';
  }

  function dayTime(ts) {
    var t = new Date(ts), today = new Date();
    return (t.toDateString() === today.toDateString() ? 'сегодня ' : t.getDate() + '.' + String(t.getMonth() + 1).padStart(2, '0') + ' ') + hhmm(t);
  }

  function renderJournal() {
    if (!panel) return;
    var d = jrSt, box = pg.jr;
    var exp = box.querySelector('[data-j="export"]'), rev = box.querySelector('[data-j="reveal"]'), clr = box.querySelector('[data-j="clear"]');
    exp.disabled = !d || !!d.busy;
    exp.textContent = d && d.busy ? 'Собираем…' : 'Выгрузить логи';
    rev.style.display = d && d.saved && !d.busy ? '' : 'none';
    clr.disabled = !d || !d.size || !!d.busy;
    clr.textContent = jrAsk ? 'Точно очистить?' : 'Очистить журнал';
    clr.classList.toggle('cct-warnbtn', !!jrAsk);
    clr.classList.toggle('cct-btn2', !jrAsk);
    if (!d) { status('jr', '', 'loading', 'Ждём ответ плагина…'); return; }
    var rec = d.lags > 0 ? 'записано лагов: ' + d.lags + (d.since ? ' с ' + dayTime(d.since) : '') + ' · ' + kb(d.size)
      : d.size ? 'лагов пока не было · ' + kb(d.size) : 'журнал пуст';
    if (d.busy) status('jr', '', 'loading', 'Собираем логи…');
    else if (d.err) status('jr', 'bad', 'warning', 'Не получилось выгрузить: ' + d.err);
    else if (d.saved) status('jr', 'ok', 'pass', 'Сохранено: ' + d.saved + ' — этот файл и отправляйте');
    else if (d.on) status('jr', 'ok', 'record', 'Запись идёт · ' + rec);
    else status('jr', '', 'circle-large-outline', 'Запись выключена' + (d.lags > 0 || d.size ? ' · ' + rec : '') + ' · логи IDE выгрузятся и без неё');
  }

  function regToggle(id, label) {
    return '<label class="cct-tg"><input type="checkbox" data-r="' + id + '">' +
      '<span class="cct-sl"></span><span class="cct-tl">' + label + '</span></label>';
  }

  function onRegistry(d) {
    regSt = d;
    renderReg();
  }

  function onRegClick(e) {
    var seg = e.target.closest('.cct-seg[data-r] button[data-v]');
    if (seg) { regSend('set:fps:' + seg.getAttribute('data-v')); return; }
    var b = e.target.closest('[data-ra]');
    if (!b || b.disabled) return;
    var act = b.getAttribute('data-ra');
    if (act === 'restart' && !regAsk) {
      // Перезапуск закроет IDE вместе с чатом — второе нажатие подтверждает.
      regAsk = setTimeout(function () { regAsk = 0; renderReg(); }, 5000);
      renderReg();
      return;
    }
    clearTimeout(regAsk);
    regAsk = 0;
    regSend(act);
  }

  function regSend(cmd) {
    pg.regBusy = true;
    send('reg:' + cmd);
    renderReg();
  }

  var REG_NAME = { fps: 'частота кадров', oop: 'отдельный процесс', gpu: 'аппаратное ускорение' };

  function renderReg() {
    if (!panel) return;
    var d = regSt, any = false, pending = [];
    pg.regBusy = false;
    if (d) ['fps', 'oop', 'gpu'].forEach(function (id) {
      var r = d[id] || { missing: true };
      if (r.changed) any = true;
      if (r.pending) pending.push(REG_NAME[id]);
    });
    // Блок виден, когда модуль включён, и всегда, если что-то уже изменено — чтобы было чем вернуть.
    pg.reg.hidden = !d || !(opt('perf', 'enabled') || any || (d && d.restart));
    if (!d) return;
    ['oop', 'gpu'].forEach(function (id) {
      var r = d[id] || {}, inp = pg.reg.querySelector('input[data-r="' + id + '"]');
      inp.checked = !!r.value;
      inp.disabled = !!r.missing;
      inp.closest('.cct-tg').style.display = r.missing ? 'none' : '';
      pg.reg.querySelector('[data-n="' + id + '"]').style.display = r.missing ? 'none' : '';
    });
    var fps = d.fps || {};
    pg.reg.querySelector('.cct-rfps').style.display = fps.missing ? 'none' : '';
    pg.reg.querySelector('[data-n="fps"]').style.display = fps.missing ? 'none' : '';
    Array.prototype.forEach.call(pg.reg.querySelectorAll('.cct-seg[data-r] button'), function (b) {
      b.classList.toggle('cct-sel', +b.getAttribute('data-v') === fps.value);
    });
    var reset = pg.reg.querySelector('[data-ra="resetall"]'), rs = pg.reg.querySelector('[data-ra="restart"]');
    reset.disabled = !any;
    rs.style.display = d.restart ? '' : 'none';
    rs.textContent = regAsk ? 'Точно перезапустить? IDE закроется' : 'Перезапустить IDE';
    rs.classList.toggle('cct-warnbtn', !!regAsk);
    if (pending.length) status('reg', 'bad', 'warning', 'Изменено: ' + pending.join(', ') + ' — заработает после перезапуска IDE');
    else if (any) status('reg', 'ok', 'pass', 'Изменённые значения уже действуют');
    else status('reg', '', 'circle-large-outline', 'Все значения по умолчанию');
  }

  function toggle(mod, key, label) {
    return '<label class="cct-tg"><input type="checkbox" data-m="' + mod + '" data-k="' + key + '">' +
      '<span class="cct-sl"></span><span class="cct-tl">' + label + '</span></label>';
  }

  // Аватарка автора для «О плагине»: 96×96 WebP прямо в скрипте, чтобы не зависеть от сети (CSP CC GUI разрешает data:).
  var AVATAR = 'data:image/webp;base64,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';

  // Вкладки страницы настроек: [ключ, значок, подпись]. Выбранная запоминается в localStorage страницы.
  var TABS = [['spotify', 'music', 'Spotify'], ['limits', 'dashboard', 'Лимиты'], ['perf', 'zap', 'Производительность'], ['about', 'info', 'О плагине']];
  var TAB_KEY = 'cctTab';

  function savedTab() {
    var t = null;
    try { t = localStorage.getItem(TAB_KEY); } catch (e) { /* хранилище недоступно */ }
    return TABS.some(function (x) { return x[0] === t; }) ? t : TABS[0][0];
  }

  function setTab(t, focus) {
    pg.tab = t;
    try { localStorage.setItem(TAB_KEY, t); } catch (e) { /* не страшно */ }
    Array.prototype.forEach.call(pg.tabs, function (b) {
      var on = b.getAttribute('data-tab') === t;
      b.classList.toggle('cct-act', on);
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
      if (on && focus) b.focus();
    });
    Array.prototype.forEach.call(panel.querySelectorAll('[data-pane]'), function (el) {
      el.hidden = el.getAttribute('data-pane') !== t;
    });
    pg.ver.hidden = t === 'about';    // там версия уже есть
  }

  function buildPanel() {
    panel = mk('div', null,
      '<div class="cct-sec">' +
      '<h3>CC GUI Tweaks</h3>' +
      '<p class="cct-desc">Дополнения к чату CC GUI. Каждый модуль выключен, пока вы его не включите, — выбор сохраняется между запусками IDE.</p>' +
      '<div class="cct-tabs" role="tablist" aria-label="Разделы CC GUI Tweaks">' +
      TABS.map(function (t) {
        return '<button type="button" class="cct-tab" role="tab" data-tab="' + t[0] + '" title="' + t[2] + '">' +
          '<span class="codicon codicon-' + t[1] + '"></span><span class="cct-tn">' + t[2] + '</span><span class="cct-dot"></span></button>';
      }).join('') + '</div>' +
      '<div class="cct-about" data-pane="about" role="tabpanel">' +
      '<div class="cct-ab-head"><div class="cct-ab-logo"><span class="codicon codicon-extensions"></span></div>' +
      '<div><div class="cct-ab-name">CC GUI Tweaks</div><div class="cct-ab-ver"></div></div></div>' +
      '<p class="cct-ab-text">Дополнения к чату плагина CC GUI в WebStorm: мини-плеер Spotify с очередью, бар лимитов Claude и режим производительности. ' +
      'Сам CC GUI не изменяется — модули встраиваются в его страницу и убираются вместе с плагином.</p>' +
      '<div class="cct-ab-row"><img class="cct-ab-ava" src="' + AVATAR + '" alt="" width="32" height="32">Автор: <b>Uximy</b></div>' +
      '<div class="cct-ab-links">' +
      '<a data-about="github"><span class="codicon codicon-github"></span>GitHub <small>github.com/Uximy</small></a>' +
      '<a data-about="site"><span class="codicon codicon-globe"></span>Himitsu FM <small>himitsu-fm.online</small></a>' +
      '</div></div>' +

      '<div class="cct-mod" data-mod="spotify" data-pane="spotify" role="tabpanel">' +
      '<div class="cct-head"><span class="codicon codicon-music"></span><span class="cct-label">Spotify</span></div>' +
      '<p class="cct-hint">Мини-плеер над полем ввода: трек, обложка (клик — показать целиком), пауза и переключение треков, ' +
      'перемотка (клик или перетаскивание по полосе внизу карточки), громкость Spotify в микшере Windows, очередь и плейлист, ' +
      'текст песни, который идёт за треком. Текст берётся из открытой базы lrclib.net: когда панель текста открыта, туда уходят ' +
      'название трека, исполнитель и альбом.</p>' +
      toggle('spotify', 'enabled', 'Показывать плеер') +
      '<div class="cct-sub">' + toggle('spotify', 'time', 'Время трека') + toggle('spotify', 'volume', 'Регулятор громкости') +
      toggle('spotify', 'queue', 'Очередь и плейлист') +
      toggle('spotify', 'lyrics', 'Текст песни') +
      '<div class="cct-row"><span>Размер плеера</span><div class="cct-seg" data-m="spotify" data-k="size">' +
      '<button type="button" data-v="1">Обычный</button><button type="button" data-v="2">Крупный</button>' +
      '</div></div>' +
      '<div class="cct-web">' +
      '<div class="cct-wl">Подключение к Spotify Web API</div>' +
      '<p class="cct-wp">Очередь берётся из Spotify Web API, поэтому нужен ключ вашего приложения. Пока Spotify не подключён, кнопки очереди в плеере нет.</p>' +
      '<ol class="cct-steps">' +
      '<li>Откройте <a data-a="dashboard">developer.spotify.com/dashboard</a> и нажмите <b>Create app</b>.</li>' +
      '<li>В <b>Redirect URIs</b> добавьте <code>http://127.0.0.1:43118/callback</code><button type="button" data-a="copy">Копировать</button>, ' +
      'в <b>APIs used</b> отметьте <b>Web API</b> и сохраните.</li>' +
      '<li>Вставьте сюда <b>Client ID</b> приложения и нажмите «Подключить» — Spotify спросит разрешение в браузере.</li>' +
      '</ol>' +
      '<div class="cct-row"><input class="cct-in" type="text" placeholder="Client ID" spellcheck="false" autocomplete="off" maxlength="32" aria-label="Client ID приложения Spotify">' +
      '<button type="button" class="cct-btn" data-a="login">Подключить</button>' +
      '<button type="button" class="cct-btn cct-btn2" data-a="logout">Отключить</button></div>' +
      '<div class="cct-status" data-s="web"></div>' +
      '</div>' +
      '</div>' +
      '<div class="cct-status" data-s="spotify"></div>' +
      '</div>' +

      '<div class="cct-mod" data-mod="limits" data-pane="limits" role="tabpanel">' +
      '<div class="cct-head"><span class="codicon codicon-dashboard"></span><span class="cct-label">Лимиты Claude</span></div>' +
      '<p class="cct-hint">Сколько израсходовано из 5-часового окна и недельных лимитов подписки — те же цифры, что /usage в Claude Code. ' +
      'Плагин берёт вход Claude Code из ~/.claude/.credentials.json и спрашивает только api.anthropic.com; токен нигде не сохраняется.</p>' +
      toggle('limits', 'enabled', 'Показывать бар лимитов') +
      '<div class="cct-sub">' + toggle('limits', 'models', 'Лимиты по отдельным моделям') + toggle('limits', 'resets', 'Время сброса') +
      '<div class="cct-row"><span>Обновлять</span><div class="cct-seg" data-m="limits" data-k="interval">' +
      '<button type="button" data-v="60">1 мин</button><button type="button" data-v="120">2 мин</button><button type="button" data-v="300">5 мин</button>' +
      '</div></div></div>' +
      '<div class="cct-status" data-s="limits"></div>' +
      '</div>' +

      '<div class="cct-mod" data-mod="perf" data-pane="perf" role="tabpanel">' +
      '<div class="cct-head"><span class="codicon codicon-zap"></span><span class="cct-label">Производительность</span></div>' +
      '<p class="cct-hint">Чат CC GUI рисуется встроенным Chromium в отдельном процессе, и каждый кадр копируется в окно IDE. ' +
      'Бесконечные анимации — спиннеры, пульсации, питомец — заставляют перерисовывать чат без остановки, поэтому во время ответа подтормаживают ввод и окна. ' +
      'Модуль их останавливает: значки загрузки остаются на месте, но не крутятся.</p>' +
      toggle('perf', 'enabled', 'Режим производительности') +
      '<div class="cct-sub">' + toggle('perf', 'anim', 'Остановить бесконечные анимации') +
      toggle('perf', 'blur', 'Без размытия фона под диалогами') + '</div>' +
      '<div class="cct-status" data-s="perf"></div>' +
      '<div class="cct-reg" hidden>' +
      '<div class="cct-wl"><span class="codicon codicon-settings"></span>Встроенный браузер IDE</div>' +
      '<p class="cct-wp">Эти переключатели меняют реестр WebStorm (Help → Find Action → Registry) и действуют на всю IDE, ' +
      'а не только на чат. Вступают в силу после перезапуска IDE. «Вернуть как было» ставит значения по умолчанию.</p>' +
      '<div class="cct-row cct-rfps"><span>Частота кадров</span><div class="cct-seg" data-r="fps">' +
      '<button type="button" data-v="30">30</button><button type="button" data-v="60">60</button><button type="button" data-v="120">120</button>' +
      '</div></div>' +
      '<p class="cct-rn" style="margin-left:0" data-n="fps">Сколько кадров в секунду браузер передаёт в окно IDE. По умолчанию 120; монитору хватает 60.</p>' +
      regToggle('oop', 'Браузер в отдельном процессе') +
      '<p class="cct-rn" data-n="oop">По умолчанию включено. Выключите — чат будет рисоваться прямо в окно, без копирования каждого кадра между процессами.</p>' +
      regToggle('gpu', 'Отключить аппаратное ускорение') +
      '<p class="cct-rn" data-n="gpu">По умолчанию выключено. Включите, если чат всё ещё подтормаживает: Chromium будет рисовать на процессоре, ' +
      'без чтения кадров обратно из видеопамяти.</p>' +
      '<div class="cct-btns"><button type="button" class="cct-btn cct-btn2" data-ra="resetall">Вернуть как было</button>' +
      '<button type="button" class="cct-btn" data-ra="restart">Перезапустить IDE</button></div>' +
      '<div class="cct-status" data-s="reg"></div>' +
      '</div>' +
      '<div class="cct-reg cct-jr">' +
      '<div class="cct-wl"><span class="codicon codicon-pulse"></span>Журнал лагов</div>' +
      '<p class="cct-wp">Пока включено, плагин записывает, когда тормозит чат (долгие кадры и какой код их вызвал) и когда не отвечает окно IDE, ' +
      'а раз в минуту — загрузку процессора и памяти. Журнал лежит на этом компьютере и никуда сам не отправляется. ' +
      '«Выгрузить логи» сохранит zip в «Загрузки»: журнал, сведения о системе и логи IDE, без токенов и имени пользователя в путях.</p>' +
      toggle('perf', 'journal', 'Записывать лаги') +
      '<div class="cct-btns"><button type="button" class="cct-btn" data-j="export">Выгрузить логи</button>' +
      '<button type="button" class="cct-btn cct-btn2" data-j="reveal">Показать в папке</button>' +
      '<button type="button" class="cct-btn cct-btn2" data-j="clear">Очистить журнал</button></div>' +
      '<div class="cct-status" data-s="jr"></div>' +
      '</div>' +
      '</div>' +

      '<div class="cct-ver"></div>' +
      '</div>');
    panel.id = 'cct-panel';
    pg.inputs = panel.querySelectorAll('input[data-m]');
    pg.segs = panel.querySelectorAll('.cct-seg[data-m]');
    pg.ver = panel.querySelector('.cct-ver');
    pg.web = panel.querySelector('.cct-web');
    pg.cid = panel.querySelector('.cct-in');
    pg.web.addEventListener('click', onWebClick);
    pg.reg = panel.querySelector('.cct-reg:not(.cct-jr)');
    pg.jr = panel.querySelector('.cct-jr');
    pg.jr.addEventListener('click', onJournalClick);
    pg.reg.addEventListener('click', onRegClick);
    pg.reg.addEventListener('change', function (e) {
      var t = e.target;
      if (t.matches && t.matches('input[data-r]')) regSend('set:' + t.getAttribute('data-r') + ':' + t.checked);
    });
    pg.abVer = panel.querySelector('.cct-ab-ver');
    pg.tabs = panel.querySelectorAll('.cct-tab');
    var tabBar = panel.querySelector('.cct-tabs');
    tabBar.addEventListener('click', function (e) {
      var b = e.target.closest('.cct-tab');
      if (b) setTab(b.getAttribute('data-tab'));
    });
    tabBar.addEventListener('keydown', function (e) {   // стрелки, Home/End — как у обычных вкладок
      var i = TABS.findIndex(function (x) { return x[0] === pg.tab; }), n = TABS.length;
      var j = e.key === 'ArrowRight' ? (i + 1) % n : e.key === 'ArrowLeft' ? (i + n - 1) % n : e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : -1;
      if (j < 0) return;
      e.preventDefault();
      setTab(TABS[j][0], true);
    });
    panel.addEventListener('click', function (e) {
      var a = e.target.closest('[data-about]');
      if (a) send('open:' + a.getAttribute('data-about'));   // github | site — открывает плагин
    });
    pg.cid.addEventListener('input', function () { if (pg.cidErr) { pg.cidErr = false; renderWeb(); } });
    pg.cid.addEventListener('change', function () {
      var v = pg.cid.value.trim();
      if (S && (v === '' || CLIENT_ID.test(v)) && S.spotify.clientId !== v) change('spotify', 'clientId', v);
    });
    pg.cid.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); webLogin(); } });
    panel.addEventListener('change', function (e) {
      var t = e.target;
      if (!t.matches || !t.matches('input[data-m]')) return;
      change(t.getAttribute('data-m'), t.getAttribute('data-k'), t.checked);
    });
    Array.prototype.forEach.call(pg.segs, function (seg) {
      seg.addEventListener('click', function (e) {
        var b = e.target.closest('button[data-v]');
        if (b) change(seg.getAttribute('data-m'), seg.getAttribute('data-k'), +b.getAttribute('data-v'));
      });
    });
    setTab(savedTab());
    renderPanel();
  }

  var CLIENT_ID = /^[0-9a-f]{32}$/i;
  var WEB_ERR = {
    noclient: 'Сначала вставьте Client ID',
    denied: 'Вход отменён в браузере',
    oauth: 'Spotify вернул ошибку входа — проверьте redirect URI в приложении',
    port: 'Порт 43118 занят другой программой — закройте её и попробуйте снова',
    timeout: 'Вход не завершён за 5 минут — нажмите «Подключить» ещё раз',
    auth: 'Вход устарел или отозван — подключитесь заново',
    network: 'Нет связи со Spotify',
    'http 400': 'Spotify не принял вход — проверьте Client ID и redirect URI'
  };

  function webLogin() {
    var v = pg.cid.value.trim();
    if (!CLIENT_ID.test(v)) { pg.cidErr = true; renderWeb(); pg.cid.focus(); return; }
    pg.cidErr = false;
    if (S && S.spotify.clientId !== v) change('spotify', 'clientId', v);
    send('spweb:login');
    webSt = Object.assign({}, webSt, { pending: true, error: null });
    renderWeb();
  }

  function onWebClick(e) {
    var a = e.target.closest('[data-a]');
    if (!a) return;
    var act = a.getAttribute('data-a');
    if (act === 'login') { webLogin(); return; }
    send('spweb:' + act);              // dashboard | copy | logout
    if (act === 'copy') { a.textContent = 'Скопировано'; setTimeout(function () { a.textContent = 'Копировать'; }, 1500); }
  }

  function renderWeb() {
    if (!panel) return;
    var w = webSt || {};
    pg.web.classList.toggle('cct-web-on', !!w.connected);
    pg.web.classList.toggle('cct-web-scope', !!w.connected && w.scope === false);
    if (document.activeElement !== pg.cid && S && S.spotify && !pg.cidErr) pg.cid.value = S.spotify.clientId || '';
    if (pg.cidErr) status('web', 'bad', 'warning', 'Client ID — 32 символа из цифр и букв a–f (Dashboard → ваше приложение → Settings)');
    else if (!webSt) status('web', '', 'loading', 'Ждём ответ плагина…');
    else if (w.connected && w.scope === false) status('web', 'bad', 'warning', 'Подключено' + (w.user ? ': ' + w.user : '') +
      ', но без разрешения включать треки — нажмите «Подключить» ещё раз');
    else if (w.connected) status('web', 'ok', 'pass', 'Подключено' + (w.user ? ': ' + w.user : ''));
    else if (w.pending) status('web', '', 'loading', 'Ждём вход в браузере… (ссылка действует 5 минут)');
    else if (w.error) status('web', 'bad', 'warning', WEB_ERR[w.error] || 'Не получилось: ' + w.error);
    else status('web', '', 'circle-large-outline', w.client ? 'Не подключено — нажмите «Подключить»' : 'Не подключено — кнопка очереди не показывается');
  }

  function change(mod, key, value) {
    if (!S) return;
    S[mod][key] = value;               // сразу показываем, плагин подтвердит рассылкой настроек
    var patch = {}; patch[mod] = {}; patch[mod][key] = value;
    send('set:' + JSON.stringify(patch));
    renderSpotify();
    renderLimits();
    perfTick();
    renderPanel();
  }

  function renderPanel() {
    if (!panel) return;
    for (var i = 0; i < pg.inputs.length; i++) {
      var inp = pg.inputs[i];
      inp.checked = opt(inp.getAttribute('data-m'), inp.getAttribute('data-k'));
      inp.disabled = !S;
    }
    ['spotify', 'limits', 'perf'].forEach(function (m) {
      panel.querySelector('[data-mod="' + m + '"]').classList.toggle('cct-off', !opt(m, 'enabled'));
      var tb = panel.querySelector('.cct-tab[data-tab="' + m + '"]');
      tb.classList.toggle('cct-on', opt(m, 'enabled'));
      tb.title = TABS.filter(function (x) { return x[0] === m; })[0][2] + (opt(m, 'enabled') ? ' — включено' : ' — выключено');
    });
    pg.web.style.display = opt('spotify', 'queue') ? '' : 'none';
    renderWeb();
    renderReg();
    renderJournal();
    var iv = S && S.limits ? S.limits.interval : 60;
    Array.prototype.forEach.call(pg.segs, function (seg) {
      var m = seg.getAttribute('data-m'), k = seg.getAttribute('data-k'), v = num(m, k, m === 'limits' ? iv : 1);
      Array.prototype.forEach.call(seg.children, function (b) { b.classList.toggle('cct-sel', +b.getAttribute('data-v') === v); });
    });
    pg.ver.textContent = S && S.meta ? 'Версия ' + S.meta.version : 'Ждём ответ плагина…';
    pg.abVer.textContent = S && S.meta ? 'Версия ' + S.meta.version : '';
    renderPanelStatus();
  }

  function status(name, kind, icon, text) {
    var el = panel.querySelector('[data-s="' + name + '"]');
    var html = '<span class="codicon codicon-' + icon + '"></span><span>' + esc(text) + '</span>';
    if (el.getAttribute('data-h') !== html) { el.innerHTML = html; el.setAttribute('data-h', html); }
    el.className = 'cct-status' + (kind ? ' cct-' + kind : '');
  }

  function renderPanelStatus() {
    if (!panel || !S) return;
    if (!spRaw) status('spotify', '', 'loading', 'Запускаем помощник плеера…');
    else if (!spRaw.ok) status('spotify', 'bad', 'warning', 'Медиа-сессии Windows сейчас недоступны');
    else if (!st) status('spotify', '', 'circle-large-outline', 'Spotify ничего не играет');
    else status('spotify', 'ok', 'pass', (st.status === 'Playing' ? 'Играет: ' : 'Пауза: ') + st.title + (st.artist ? ' — ' + st.artist : '') +
      (localVol >= 0 ? ' · громкость ' + localVol + '%' : ''));

    var d = limData;
    if (!d) status('limits', '', 'loading', 'Загружаем…');
    else if (d.error && !d.stale) status('limits', 'bad', 'warning', 'Не удалось получить лимиты: ' + limitError(d.error));
    else {
      var items = limitItems(d), at = d.at ? new Date(d.at) : null;
      var text = items.length ? items.map(function (i) { return i.label + ' ' + Math.round(i.pct) + '%'; }).join(', ') : 'Сервер не вернул лимитов';
      if (d.stale) status('limits', 'bad', 'warning', text + ' — данные устарели: ' + limitError(d.error));
      else status('limits', 'ok', 'pass', text + (at ? ' · обновлено в ' + hhmm(at) : ''));
    }

    if (!opt('perf', 'anim') && !opt('perf', 'blur')) status('perf', '', 'circle-large-outline', 'Обе настройки выключены — страница работает как обычно');
    else if (opt('perf', 'anim')) status('perf', 'ok', 'pass', 'Остановлено анимаций CC GUI: ' + perfCount + (opt('perf', 'blur') ? ' · размытие выключено' : ''));
    else status('perf', 'ok', 'pass', 'Размытие под диалогами выключено, анимации не тронуты');
  }

  // Запуск в самом конце: при внедрении в готовую страницу start() вызывается сразу,
  // и все var выше к этому моменту уже должны быть присвоены.
  // Пока ждём, __cctStop — это отмена ожидания. Повторное внедрение подменяет её, и старый цикл выходит сам.
  var tries = 0, cancel = function () { delete window.__cctStop; };
  window.__cctStop = cancel;
  (function wait() {
    if (window.__cctOn || window.__cctStop !== cancel) return;
    if (document.body && isCcGui()) { window.__cctOn = true; start(); return; }
    if (++tries < 40) setTimeout(wait, 500);
  })();
})();
