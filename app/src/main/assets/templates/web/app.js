let count = 0;
document.getElementById('btn').addEventListener('click', () => {
  count++;
  document.getElementById('counter').textContent = '点击次数：' + count;
});
