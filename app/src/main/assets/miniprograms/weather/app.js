App({
  globalData: {
    engine: 'MiniAppEngine (native C++ JS engine)'
  },
  onLaunch: function (options) {
    console.log('[weather app] onLaunch', JSON.stringify(options));
  }
});
