package com.ngheviet.app

object SessionState {
    @Volatile var running = false
    @Volatile var status = "Chưa bắt đầu"
    @Volatile var english = ""
    @Volatile var vietnamese = ""
    @Volatile var metrics = ""
    @Volatile var rate = 1.7f
}
