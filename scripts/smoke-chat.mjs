import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';

const base = process.env.CHAT_API_URL || 'http://127.0.0.1:8093';
const username = `smoke_${Date.now()}`;
const password = randomBytes(24).toString('hex');
async function account(path) {
  const response = await fetch(`${base}/api/v1/auth/${path}`, {
    method: 'POST', body: new URLSearchParams({ username, password }),
    signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.code, '0000', result.info);
  return result.data.token;
}

const token = await account('register');
const loginToken = await account('password/login');
async function chat(messages) {
  const response = await fetch(`${base}/api/v1/chatgpt/chat/completions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: loginToken },
    body: JSON.stringify({ model: process.env.CHAT_MODEL || 'deepseek-flash', messages }), signal: AbortSignal.timeout(90000),
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /text\/event-stream/);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let answer = '', chunks = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks++;
    answer += decoder.decode(value, { stream: true });
  }
  answer += decoder.decode();
  assert.ok(answer.trim(), 'Expected a non-empty model reply');
  assert.ok(!answer.includes('[错误]'), answer);
  console.log(JSON.stringify({ chunks, answer }));
  return answer;
}

try {
  const messages = [{ role: 'user', content: '请记住暗号是 K7M9，只回复：记住了。' }];
  messages.push({ role: 'assistant', content: await chat(messages) });
  messages.push({ role: 'user', content: '刚才的暗号是什么？只回复暗号。' });
  assert.match(await chat(messages), /K7M9/);
  const unauthorized = await fetch(`${base}/api/v1/chatgpt/chat/completions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'invalid-token' },
    body: JSON.stringify({ model: 'deepseek-chat', messages }), signal: AbortSignal.timeout(10000),
  });
  assert.equal((await unauthorized.text()).trim(), '0003');
  console.log('PASS: registration, password login, streaming, context and invalid-token rejection');
} finally {
  for (const session of new Set([token, loginToken])) {
    await fetch(`${base}/api/v1/auth/logout`, {
      method: 'POST', headers: { Authorization: session }, signal: AbortSignal.timeout(10000),
    });
  }
}
