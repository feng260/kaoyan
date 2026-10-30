(function () {
  'use strict';

  // --- 倒计时：距预计初试 2027-12-18 08:30 ---
  var target = new Date('2027-12-18T08:30:00+08:00');
  var numEl = document.getElementById('countdown-num');
  var labelEl = document.getElementById('countdown-label');

  function tick() {
    if (!numEl) return;
    var diff = target.getTime() - Date.now();
    if (diff <= 0) {
      numEl.textContent = '0';
      if (labelEl) labelEl.textContent = '初试已开考 · 把会做的都做对';
      return;
    }
    var days = Math.ceil(diff / 86400000);
    numEl.textContent = days;
  }
  tick();
  setInterval(tick, 60000);

  // --- 日计划模板切换 ---
  var tabs = Array.prototype.slice.call(document.querySelectorAll('.tab-btn'));
  var panes = Array.prototype.slice.call(document.querySelectorAll('.tab-pane'));

  tabs.forEach(function (btn) {
    btn.addEventListener('click', function () {
      tabs.forEach(function (b) {
        b.classList.remove('active');
        b.setAttribute('aria-selected', 'false');
      });
      panes.forEach(function (p) { p.classList.remove('active'); });
      btn.classList.add('active');
      btn.setAttribute('aria-selected', 'true');
      var pane = document.getElementById(btn.getAttribute('data-target'));
      if (pane) pane.classList.add('active');
    });
  });

  // --- 滚动显现 ---
  if ('IntersectionObserver' in window) {
    var obs = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) {
          e.target.classList.add('in');
          obs.unobserve(e.target);
        }
      });
    }, { threshold: 0.06 });
    document.querySelectorAll('.reveal').forEach(function (el) { obs.observe(el); });
  } else {
    document.querySelectorAll('.reveal').forEach(function (el) { el.classList.add('in'); });
  }
})();
