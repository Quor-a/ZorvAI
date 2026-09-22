var CITIES = [
  { name: '深圳', lat: 22.54, lon: 114.06 },
  { name: '北京', lat: 39.90, lon: 116.40 },
  { name: '上海', lat: 31.23, lon: 121.47 },
  { name: '广州', lat: 23.13, lon: 113.26 },
  { name: '成都', lat: 30.57, lon: 104.07 },
  { name: '杭州', lat: 30.27, lon: 120.15 }
];

var WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function wmo(code) {
  if (code === 0) return { emoji: '☀️', text: '晴' };
  if (code === 1) return { emoji: '🌤️', text: '晴间多云' };
  if (code === 2) return { emoji: '⛅', text: '多云' };
  if (code === 3) return { emoji: '☁️', text: '阴' };
  if (code >= 45 && code <= 48) return { emoji: '🌫️', text: '雾' };
  if (code >= 51 && code <= 57) return { emoji: '🌦️', text: '毛毛雨' };
  if (code >= 61 && code <= 67) return { emoji: '🌧️', text: '雨' };
  if (code >= 71 && code <= 77) return { emoji: '🌨️', text: '雪' };
  if (code >= 80 && code <= 82) return { emoji: '🌦️', text: '阵雨' };
  if (code >= 85 && code <= 86) return { emoji: '🌨️', text: '阵雪' };
  if (code >= 95 && code <= 99) return { emoji: '⛈️', text: '雷阵雨' };
  return { emoji: '🌡️', text: '未知' };
}

function weekdayOf(s) {
  var p = s.split('-');
  var y = parseInt(p[0], 10);
  var m = parseInt(p[1], 10);
  var d = parseInt(p[2], 10);
  var dt = new Date(y, m - 1, d, 12, 0, 0);
  return WEEK[dt.getDay()];
}

Page({
  data: {
    cityIndex: 0,
    city: '深圳',
    updatedAt: '',
    loading: true,
    ready: false,
    error: '',
    curEmoji: '🌡️',
    curTemp: '--',
    curText: '',
    feelsLike: '--',
    humidity: '--',
    wind: '--',
    forecast: []
  },

  onLoad: function () {
    this.fetchWeather();
  },

  fetchWeather: function () {
    var self = this;
    var city = CITIES[this.data.cityIndex];
    self.setData({ loading: true, ready: false, error: '' });
    var url = 'https://api.open-meteo.com/v1/forecast'
      + '?latitude=' + city.lat
      + '&longitude=' + city.lon
      + '&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,weather_code'
      + '&daily=weather_code,temperature_2m_max,temperature_2m_min'
      + '&timezone=auto&forecast_days=7';
    wx.request({
      url: url,
      success: function (res) {
        if (res.statusCode !== 200 || !res.data) {
          self.setData({ loading: false, ready: false, error: '天气数据异常（' + res.statusCode + '）' });
          return;
        }
        var d = res.data;
        if (d && d.charAt) {
          try { d = JSON.parse(d); } catch (e2) { d = null; }
        }
        if (!d) {
          self.setData({ loading: false, ready: false, error: '天气数据解析失败' });
          return;
        }
        var cur = d.current || {};
        var codeInfo = wmo(cur.weather_code || 0);
        var daily = d.daily || {};
        var times = daily.time || [];
        var codes = daily.weather_code || [];
        var maxs = daily.temperature_2m_max || [];
        var mins = daily.temperature_2m_min || [];
        var list = [];
        for (var i = 0; i < times.length; i++) {
          var info = wmo(codes[i] || 0);
          var label = (i === 0) ? '今天' : weekdayOf(times[i]);
          list.push({
            day: label,
            emoji: info.emoji,
            text: info.text,
            hi: Math.round(maxs[i]) + '°',
            lo: Math.round(mins[i]) + '°'
          });
        }
        var now = new Date();
        var hh = now.getHours();
        var mm = now.getMinutes();
        if (hh < 10) hh = '0' + hh;
        if (mm < 10) mm = '0' + mm;
        self.setData({
          loading: false,
          ready: true,
          error: '',
          city: city.name,
          updatedAt: '更新于 ' + hh + ':' + mm,
          curEmoji: codeInfo.emoji,
          curTemp: Math.round(cur.temperature_2m || 0) + '°',
          curText: codeInfo.text,
          feelsLike: Math.round(cur.apparent_temperature || 0) + '°',
          humidity: (cur.relative_humidity_2m || 0) + '%',
          wind: Math.round(cur.wind_speed_10m || 0) + ' km/h',
          forecast: list
        });
      },
      fail: function (err) {
        self.setData({ loading: false, ready: false, error: '网络请求失败，请检查网络后重试' });
      }
    });
  },

  onRefresh: function () {
    this.fetchWeather();
  },

  onSwitchCity: function () {
    var self = this;
    var names = [];
    for (var i = 0; i < CITIES.length; i++) names.push(CITIES[i].name);
    wx.showActionSheet({
      itemList: names,
      success: function (res) {
        self.setData({ cityIndex: res.tapIndex });
        self.fetchWeather();
      }
    });
  }
});
