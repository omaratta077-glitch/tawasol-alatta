window.TAWASOL_CONFIG = {
  // بعد رفع TURN Server ضع البيانات هنا.
  // بدون TURN ستعمل المكالمات في كثير من الشبكات، لكن ليس كلها.
  iceServers: [
    { urls: "stun:stun.l.google.com:19302" }
    // مثال TURN:
    // {
    //   urls: "turn:turn.example.com:3478",
    //   username: "USERNAME",
    //   credential: "PASSWORD"
    // }
  ]
};
