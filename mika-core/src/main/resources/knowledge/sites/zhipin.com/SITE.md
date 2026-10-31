---
name: zhipin.com
display: BOSS直聘
domains: ["zhipin.com"]
aliases: ["BOSS直聘", "Boss直聘", "boss直聘", "BOSS直聘网页版", "直聘", "zhipin"]
apps: ["boss-zhipin"]
open: www.zhipin.com
mode: desktop
pace: careful
description: 招聘求职网站,按城市、关键词和筛选条件搜职位,看职位详情和薪资
---
## 是什么
BOSS直聘的网页版,求职者在这里搜职位、看职位详情。另有同名手机 App(见 `boss-zhipin`)。

## 页面与网址
- 首页:打开 `https://www.zhipin.com/` 会跳到某个城市的首页,如 `/shenzhen/?seoRefer=index`;顶部「<城市>[切换]」可换城市。
- 搜索:`https://www.zhipin.com/web/geek/jobs?query=<编码关键词>&city=<城市代码>`,直接打开即可(写成 `/web/geek/job?…` 会跳到 `/web/geek/jobs`)。页面标题是「「上海招聘」-…」这样,可据此核对城市。
- 城市代码是 9 位数,如全国 100010000、北京 101010100、上海 101020100;全部城市见 `references/cities.md`。
- 筛选:在页面上选了筛选项,网址里会加上参数,如工作经验 3-5年 `experience=105`、学历本科 `degree=203`、薪资 20-50K `salary=406`、公司规模 100-499人 `scale=303`;各项的代码见 `references/filters.md`。
- 推荐职位:登录后,`/web/geek/jobs` 不带搜索词打开就是按求职期望推荐的列表;`/web/geek/job-recommend` 和首页「精选职位」的「查看更多」也会跳到这里。列表上方一排标签:「推荐」、每条求职期望各一个(职位名加城市)、末尾一个「+」新增求职期望;标签下面是筛选行(城市、求职类型、薪资待遇、工作经验、学历要求、公司行业、公司规模、「清空」)。
- 职位详情:`/job_detail/<职位编号>.html`,编号是一串二十多位的字母数字。列表里每个职位的链接就是这个地址。

## 功能
- **职位列表**:要登录才看得全。每个职位有名称、薪资、地点、经验、学历、公司名和公司的融资阶段、规模、行业,以及标签、福利和招聘人。
- **列表数据**:列表来自页面自己发出的 `POST /wapi/zpgeek/search/joblist.json`,一次 15 条,刚打开时连着发两次(第 1–15 条、第 16–30 条)。返回的 `zpData` 里有 `resCount`(结果总数)、`hasMore`、`lid` 和 `jobList`;每个职位的字段见 `references/joblist-fields.md`,薪资明文在 `salaryDesc`(如「40-70K·15薪」)。这几个响应可以用 web_capture 读到正文。
- **职位详情**:在列表里点职位卡片,详情显示在同一页右侧,网址不变;打开详情页地址看到的是整页。详情页上有职位名、招聘状态、薪资、地点、经验、学历、公司(融资阶段、规模、行业)、招聘人(名字、职务、是否在线)、福利、职位标签、职位描述、公司介绍、工商信息、工作地址和更新时间;详情页正文里的薪资是正常的文字。
- **立即沟通**:向这个职位的招聘人发起沟通。职位卡片上没有这个按钮(卡片里能点的只有职位名和公司名两个链接,点卡片别处在右侧显示详情);搜索页和推荐页右侧的详情里,按钮在职位名下面那一行(和「收藏」「举报」「微信扫码分享」在一起);职位详情页上在页面顶部,旁边是「感兴趣」。
- **登录**:`https://www.zhipin.com/web/user/`,可用手机号加短信验证码、微信或 App 扫码登录。

## 接口
- 筛选项:`https://www.zhipin.com/wapi/zpgeek/search/job/condition.json`,当成网页打开就是 JSON,有 `experienceList`、`degreeList`、`salaryList`、`scaleList`、`stageList`、`jobTypeList` 等,每项是 `{code, name}`。
- 城市:`https://www.zhipin.com/wapi/zpgeek/search/job/hot/city.json`(热门城市)和 `https://www.zhipin.com/wapi/zpCommon/data/cityGroup.json`(全部城市,按拼音首字母分组),同样是 JSON。

## 已知现象
- 列表和右侧详情里的薪资数字用网站自己的字体画出来,读到的文字缺了数字,只剩「-K·薪」或「元」这样的字;同一个职位在 `joblist.json` 的 `salaryDesc` 里是明文。
- 没登录时:顶部是「登录/注册」,列表只放出前几条(看到过 4 条),下面写着「登录账号，查看更多好职位」。
- 第一次打开搜索页时,网址可能先带上 `_security_check=1_<时间戳>`,页面加载一下后去掉。
- 猎头发布的职位标着「猎头」,公司名显示成「某知名制造上市公司」这样的匿名说法。
- 推荐列表里会混进和求职期望不太相关的职位(如销售、招商类)。
- 右侧详情里有「去App 与BOSS随时沟通」「前往App」的引导。
- 薪资筛选按区间有交集算:选 20-50K 时,列表里也有 11-22K 的职位。
- 当天能沟通的人数用完后再点「立即沟通」:页面弹出一个框,标题「您已达到沟通上限」,下面是「您今天已与150位BOSS沟通，休息一下，明天再来吧～」(看到过的人数是 150),只有一个「确定」按钮;网址不变,弹框盖在原页面上。
