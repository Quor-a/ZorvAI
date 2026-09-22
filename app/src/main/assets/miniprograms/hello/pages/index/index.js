Page({
  data: {
    title: 'Hello 自研小程序',
    count: 0,
    items: ['C++ 自研 JS 引擎', '自研 Flex 布局', 'SurfaceView 自绘渲染', 'wx.* 原生能力'],
    systemInfo: ''
  },

  onLoad: function (options) {
    console.log('[page] onLoad', JSON.stringify(options));
  },

  onReady: function () {
    console.log('[page] onReady');
  },

  onPlus: function () {
    this.setData({ count: this.data.count + 1 });
  },

  onMinus: function () {
    this.setData({ count: this.data.count - 1 });
  },

  onSystemInfo: function () {
    var self = this;
    var info = wx.getSystemInfoSync();
    self.setData({
      systemInfo: info.platform + ' / ' + info.model + ' / ' + info.windowWidth + 'x' + info.windowHeight
    });
    wx.showToast({ title: '已读取', duration: 1200 });
  }
});
