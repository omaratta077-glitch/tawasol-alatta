                                                fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    SectionTitle("معلومات عني")
                                    PremiumAuthOutlineButton(if (editProfileDetails) "إلغاء التعديل" else "تعديل المعلومات") {
                                        editProfileDetails = !editProfileDetails
                                    }
                                    if (editProfileDetails) {
                                    OutlinedTextField(profileBio, { profileBio = it.take(300) },
                                        label = { Text("نبذة عني") }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileCountry, { profileCountry = it.take(60) },
                                        label = { Text("البلد") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileCity, { profileCity = it.take(60) },
                                        label = { Text("المدينة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileEducation, { profileEducation = it.take(100) },
                                        label = { Text("التعليم / الجامعة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileWork, { profileWork = it.take(100) },
                                        label = { Text("العمل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = allowFriendRequests, onCheckedChange = { allowFriendRequests = it })
                                        Text(" استقبال طلبات الصداقة", color = GoldLight)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = accountPrivate, onCheckedChange = { accountPrivate = it })
                                        Text(" حساب خاص (المنشورات للأصدقاء فقط)", color = GoldLight)
                                    }
                                    GoldButton("حفظ المعلومات", !busy) {
                                        task {
                                            val updated = post("/api/profile", JSONObject()
                                                .put("bio", profileBio)
                                                .put("country", profileCountry)
                                                .put("city", profileCity)
                                                .put("education", profileEducation)
                                                .put("work", profileWork)
                                                .put("allowFriendRequests", allowFriendRequests)
                                                .put("accountPrivate", accountPrivate))
                                            profile = updated.optJSONObject("user") ?: profile
                                            editProfileDetails = false
                                            featureMessage = "تم حفظ معلومات الملف الشخصي"
                                        }
                                    }
                                    } else {
                                        listOf("الدولة" to profileCountry, "المدينة" to profileCity,
                                            "التعليم" to profileEducation, "العمل" to profileWork).forEach { (label, value) ->
                                            if (value.isNotBlank()) Text("$label: $value", color = White)
                                        }
                                        Text("طلبات الصداقة: ${if (allowFriendRequests) "مسموحة" else "مخفية"}", color = Muted)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    PremiumAuthOutlineButton("تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                            item { SectionTitle("منشوراتي") }
                            if (ownPosts.isEmpty()) item { Text("لا توجد منشورات ظاهرة حاليًا", color = White) }
                            items(ownPosts, key = { "own_" + it.optString("id", it.hashCode().toString()) }) { post ->
                                CardBox {
                                    Text(profile.name(), color = GoldLight, fontWeight = FontWeight.Bold)
                                    if (post.optString("text").isNotBlank()) Text(post.optString("text"), color = White)
                                    if (post.optString("image").isNotBlank()) ExpandableProfileMedia(post.optString("image"), "صورة منشوري", Modifier.fillMaxWidth().heightIn(max = 320.dp), ContentScale.Fit)
                                    if (post.optString("video").isNotBlank()) Text("منشور فيديو", color = GoldLight)
                                }
                            }
                        }
                    }
                    if (tab != "الرسائل" || chatWith.isBlank()) PremiumBottomNav(tab, ::navigate)
                }
                if (token.isNotBlank()) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), modifier = Modifier.padding(12.dp))
                }
            }
            if (profilePeer.isNotBlank() && token.isNotBlank()) {
                Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                    PublicUserProfile(
                        username = profilePeer, profile = publicProfile, recentPosts = publicPosts,
                        isLoading = publicProfileLoading,
                        onBack = { profilePeer = ""; publicProfile = null },
                        onMessage = {
                            val peer = profilePeer
                            profilePeer = ""; publicProfile = null
                            openChat(peer)
                        },
                        friendUsers = publicFriends,
                        onOpenFriend = { friend -> openPublicProfile(friend) },
                        onFriendAction = { action -> updateFriend(profilePeer, action) },
                        onVoiceCall = { openCall(profilePeer, "audio") },
                        onVideoCall = { openCall(profilePeer, "video") }
                    )
                }
            }
            activeCall?.let { session ->
                // Keep the same Composable and WebView mounted while minimized.
                // Removing/reattaching the WebView would destroy the WebRTC connection.
                NativeCallScreen(
                    session = session, token = token, myUsername = myUsername,
                    minimized = callMinimized,
                    modifier = if (callMinimized) {
                        // Full-width ongoing-call banner while browsing inside the app.
                        // Keep the WebRTC composable mounted so media is not interrupted.
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = 72.dp, start = 10.dp, end = 10.dp)
                            .height(86.dp)
                    } else Modifier.fillMaxSize().safeDrawingPadding(),
                    onMinimize = { callMinimized = true },
                    onRestore = { callMinimized = false },
                    onClose = { callMinimized = false; activeCall = null }
                )
            }
            }
        }
    }
}
