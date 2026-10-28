// Entirely synthetic fixtures. Never use customer data in browser acceptance tests.
const exactMultiline = 'Delivery was quick, and the box was intact.\nSupport answered my question.';
const hostileText = '<img src=x onerror="window.__reviqInjected=true"> Excellent quality.';
const csv = [
  '\uFEFFtext,rating,date,title,author,source_url',
  '"Delivery was quick, and the box was intact.\nSupport answered my question.",5,2026-10-01,Fast delivery,Avery,https://example.com/reviews/1',
  '"Delivery was quick, and the box was intact.\nSupport answered my question.",5,2026-10-01,Duplicate,Avery,https://example.com/reviews/1',
  'The return took two weeks.,1,2026-10-12,Slow return,Jordan,https://example.com/reviews/2',
  'Useful product but no supplied rating.,9,not-a-date,,,javascript:alert(1)',
  ',4,2026-10-13,Empty body,Casey,',
].join('\r\n');
const paste = `${hostileText}\n\nThe packaging was recyclable and delivery took two days.\n\n${hostileText}`;
module.exports = {csv, exactMultiline, hostileText, paste};
