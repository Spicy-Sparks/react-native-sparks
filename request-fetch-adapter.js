const packageJson = require("./package.json");

module.exports = {
  async request(verb, url, requestBody, callback) {
    if (typeof requestBody === "function") {
      callback = requestBody;
      requestBody = null;
    }

    const headers = {
      "Accept": "application/json",
      "Content-Type": "application/json",
      "X-CodePush-Plugin-Name": packageJson.name,
      "X-CodePush-Plugin-Version": packageJson.version,
      "X-CodePush-SDK-Version": packageJson.dependencies["sparks"]
    };

    if (requestBody && typeof requestBody === "object") {
      requestBody = JSON.stringify(requestBody);
    }

    try {
      const response = await fetch(url, {
        method: getHttpMethodName(verb),
        headers: headers,
        body: requestBody
      });

      const statusCode = response.status;
      const body = await response.text();
      callback(null, { statusCode, body });
    } catch (err) {
      callback(err);
    }
  }
};

function getHttpMethodName(verb) {
  return [
    "GET",
    "HEAD",
    "POST",
    "PUT",
    "DELETE",
    "TRACE",
    "OPTIONS",
    "CONNECT",
    "PATCH"
  ][verb];
}
