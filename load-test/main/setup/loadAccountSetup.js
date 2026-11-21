// user1@example.com ~ userN@example.com 형태의 이메일 배열 생성
function generateEmails(count, domain = "example.com", prefix = "user") {
    const emails = new Array(count);
    for (let i = 1; i <= count; i++) {
        emails[i - 1] = `${prefix}${i}@${domain}`;
    }
    return emails;
}

export function loadAccountSetup(count) {
    const emails = generateEmails(count);

    console.log(`총 이메일 수: ${emails.length}`);
    console.log(`예시: ${emails[0]}, ${emails[emails.length - 1]}`);

    return emails;
}
