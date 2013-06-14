vertx.eventBus.registerHandler('seService') { message ->
    def body = message?.body
    switch(body?.action) {
        case 'syncFavorites':
            message.reply([status: 'pending'])
            new ManageUpdates(vertx, container.config).syncFavorites(body.payload?.accountId) {->
                println "FINALLY done syncing"
            }
            break
    }
}

