App({
  globalData: {
    sdk: 'MiniAppEngine'
  },
  onLaunch: function (options) {
    console.log('[app] onLaunch', JSON.stringify(options));
  },
  onShow: function () {
    console.log('[app] onShow');
  },
  onHide: function () {
    console.log('[app] onHide');
  }
});
