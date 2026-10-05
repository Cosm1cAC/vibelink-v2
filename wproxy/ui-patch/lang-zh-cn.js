/* 将 zashboard 界面语言一次性设为简体中文。
 *
 * 原理：zashboard 用 localStorage['config/language'] 保存语言（值为 'zh-CN'）。
 * 本脚本在应用初始化前写入一次，并用标记位保证只强制一次——
 * 之后你若在 设置 → 通用 → 语言 里手动改过，不会再次覆盖。
 */
(function () {
  'use strict';
  try {
    var FLAG = 'wproxy/lang-forced-zh-cn';
    if (!localStorage.getItem(FLAG)) {
      localStorage.setItem('config/language', 'zh-CN');
      localStorage.setItem(FLAG, '1');
    }
  } catch (e) {}
})();
