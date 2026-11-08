import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
const source = fs.readFileSync(new URL('../../app/src/main/java/io/github/jackwener/dsh/core/adb/WeChatDebugProbe.kt', import.meta.url), 'utf8')
const script = source.match(/val expression =\s*"""([\s\S]*?)"""/)?.[1]
assert.ok(script)
const node = (innerText, children = {}, visible = true, attributes = {}) => ({
  innerText, getClientRects: () => visible ? [{}] : [], querySelector: selector => children[selector] || null,
  getAttribute: key => attributes[key] || null, id: attributes.id || '', querySelectorAll: () => [],
})
const area = (text, visible = true) => node(`阅读 ${text}`, {'[id^="readNum"]': node(text)}, visible)
function extract({areas = [], comments = [], count = null, commentArea = null, footerCount = null, headerCount = null, href = 'https://mp.weixin.qq.com/s?__biz=account&mid=123&idx=1&key=secret', globals = {}} = {}) {
  return vm.runInNewContext(script, {
    URL, location: {href},
    document: {
      title: '文章标题', readyState: 'complete', visibilityState: 'visible',
      querySelector: selector => ({'#activity-name': node('文章标题'), '#js_name': node('公众号'), '#js_cmt_num': count, '#js_cmt_area': commentArea, '#js_comment_bottom_num': footerCount, '#js_cmt_container .comment-flex_title': headerCount})[selector] || null,
      querySelectorAll: selector => selector === '[id^="js_read_area"]' ? areas : comments,
    }, getComputedStyle: () => ({ visibility: 'visible' }), ...globals,
  })
}
test('reads visible footer and ignores stale or body numbers', () => {
  assert.equal(extract({areas: [area('74', false), area('75')]}).read_text, '75')
  assert.equal(extract({areas: [area('10万+')]}).read_text, '10万+')
  assert.equal(extract().read_text, null)
})
test('stable identity excludes expiring credentials and changes across articles', () => {
  const a = extract(); assert.equal(a.article_identity, 'account:123:1')
  assert.equal(a.account_identity, 'account'); assert.ok(!JSON.stringify(a).includes('secret'))
  assert.notEqual(a.article_identity, extract({href: 'https://mp.weixin.qq.com/s?__biz=account&mid=124&idx=1'}).article_identity)
  assert.equal(extract({href: 'https://mp.weixin.qq.com/s'}).article_identity, null)
})
test('supports short links and page identity globals', () => {
  assert.equal(extract({href: 'https://mp.weixin.qq.com/s/abc'}).article_identity, '/s/abc')
  assert.equal(extract({href: 'https://mp.weixin.qq.com/s', globals: {biz:'b',mid:'m',idx:2}}).article_identity, 'b:m:2')
})
test('collects only loaded visible comments and keeps total separate', () => {
  const item = node('', {'.nickname': node('读者'), '.js_comment_content': node('留言正文'), '.praise_num': node('3')}, true, {'data-content-id':'c1'})
  const hidden = node('', {'.js_comment_content': node('隐藏旧留言')}, false)
  const result = extract({comments: [item,hidden], count: node('留言(25)')})
  assert.equal(result.comment_count_text, '25'); assert.equal(result.comments.length,1)
  assert.equal(result.comments[0].text,'留言正文'); assert.equal(result.comment_status,'loaded_partial')
})
test('unknown, empty and loaded-end states do not invent zero totals', () => {
  assert.equal(extract().comment_status,'not_loaded')
  assert.equal(extract({commentArea: node('暂无留言')}).comment_status,'explicit_empty')
  assert.equal(extract({commentArea: node('已显示全部留言')}).comment_status,'loaded_end')
  assert.equal(extract({commentArea: node('暂无留言')}).comment_count_text,null)
})
test('reports further batches instead of silently treating first 500 comments as complete', () => {
  const comments = Array.from({length: 600}, (_, i) => node('', {'.js_comment_content': node('留言'+i)}, true, {'data-content-id':String(i)}))
  const result = extract({comments})
  assert.equal(result.comments.length,500)
  assert.equal(result.comment_nodes_loaded,600)
  assert.equal(result.comments_next_offset,500)
})

test('article end navigation uses full document height and never scrolls hidden targets', () => {
  const navigation = source.match(/const val SCROLL_END = """([\s\S]*?)"""/)?.[1]
  assert.ok(navigation)
  for (const visibilityState of ['visible','hidden']) {
    const calls=[]
    const result=vm.runInNewContext(navigation, {
      document: {visibilityState, body:{scrollHeight:20000}, documentElement:{scrollHeight:22000}},
      window: {scrollTo: (...args) => calls.push(args)},
    })
    assert.equal(result, visibilityState === 'visible')
    assert.deepEqual(calls, visibilityState === 'visible' ? [[0,22000]] : [])
  }
})
test('nickname fallback cannot masquerade as a verified account identity', () => {
  assert.equal(extract().account_identity_verified,true)
  assert.equal(extract({href:'https://mp.weixin.qq.com/s/short'}).account_identity_verified,false)
})

test('new WeChat footer count and public discuss nickname are extracted', () => {
  const comment=node('', {'.discuss_user_nickname':node('Mike'),'.discuss_message_content':node('真实公开留言')},true,{id:'cid123'})
  const result=extract({footerCount:node('1'),comments:[comment]})
  assert.equal(result.comment_count_text,'1')
  assert.equal(result.comments[0].id,'cid123')
  assert.equal(result.comments[0].author,'Mike')
  assert.equal(result.comments[0].text,'真实公开留言')
})
test('comment navigation opens only a visible numbered public-list button', () => {
  const navigation=source.match(/const val OPEN_COMMENTS = """([\s\S]*?)"""/)?.[1]
  assert.ok(navigation)
  for (const config of [
    {text:'1',visible:true,button:true,expected:true},
    {text:'写留言',visible:true,button:true,expected:false},
    {text:'0',visible:true,button:true,expected:false},
    {text:'1',visible:false,button:true,expected:false},
    {text:'1',visible:true,button:false,expected:false},
  ]) {
    let clicks=0
    const result=vm.runInNewContext(navigation,{
      document:{visibilityState:'visible',querySelector:()=>({innerText:config.text,getClientRects:()=>config.visible?[{}]:[],closest:()=>config.button?{click:()=>clicks++}:null})},
      getComputedStyle:()=>({visibility:'visible'}),
    })
    assert.equal(result,config.expected)
    assert.equal(clicks,config.expected?1:0)
  }
})

test('visible comment header provides total when footer number is unavailable', () => {
  assert.equal(extract({headerCount:node('留言 185')}).comment_count_text,'185')
})
test('comment text cannot mark a nonempty list as explicitly empty', () => {
  const comment=node('',{'.discuss_message_content':node('暂无留言')})
  assert.equal(extract({comments:[comment],commentArea:node('暂无留言')}).comment_status,'loaded_partial')
})

test('loaded replies retain stable identity and parent without replacing parent text', () => {
  const reply=node('',{'.discuss_user_nickname':node('作者'),'.discuss_message .actual-content':node('作者的公开回复'),'[id^="js_discuss_user_nickname_"]':node('',{},true,{id:'reply-1'})})
  reply.classList={contains:s=>s==='js_reply_item'}
  reply.closest=()=>({id:'cid-parent'})
  const result=extract({comments:[reply]})
  assert.equal(result.comments[0].id,'reply-1')
  assert.equal(result.comments[0].text,'作者的公开回复')
  assert.equal(result.comments[0].kind,'reply')
  assert.equal(result.comments[0].parent_id,'cid-parent')
})
