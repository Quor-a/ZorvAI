var STORAGE_KEY = 'todo_list';

Page({
  data: {
    todos: [],
    draft: '',
    total: 0,
    doneCount: 0
  },

  onLoad: function () {
    var saved = wx.getStorageSync(STORAGE_KEY);
    if (saved && saved.length) {
      this.setData({ todos: saved });
      this.refreshStats();
    }
  },

  onInput: function (e) {
    this.setData({ draft: e.detail.value });
  },

  onAdd: function () {
    var text = (this.data.draft || '').trim();
    if (!text) {
      wx.showToast({ title: '请输入内容' });
      return;
    }
    var list = this.data.todos.slice(0);
    list.push({ text: text, done: false });
    this.setData({ todos: list, draft: '' });
    this.refreshStats();
    this.persist();
  },

  onToggle: function (e) {
    var index = e.target.dataset.index;
    var list = this.data.todos.slice(0);
    list[index].done = !list[index].done;
    this.setData({ todos: list });
    this.refreshStats();
    this.persist();
  },

  onRemove: function (e) {
    var index = e.target.dataset.index;
    var list = this.data.todos.slice(0);
    list.splice(index, 1);
    this.setData({ todos: list });
    this.refreshStats();
    this.persist();
  },

  onClearFinished: function () {
    var list = this.data.todos.filter(function (t) { return !t.done; });
    this.setData({ todos: list });
    this.refreshStats();
    this.persist();
  },

  refreshStats: function () {
    var done = 0;
    for (var i = 0; i < this.data.todos.length; i++) {
      if (this.data.todos[i].done) done++;
    }
    this.setData({ total: this.data.todos.length, doneCount: done });
  },

  persist: function () {
    wx.setStorageSync(STORAGE_KEY, this.data.todos);
  }
});
