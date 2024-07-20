package com.simplemobiletools.smsmessenger.receivers

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import com.simplemobiletools.commons.extensions.isNumberBlocked
import com.simplemobiletools.commons.extensions.normalizePhoneNumber
import com.simplemobiletools.commons.extensions.showErrorToast
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.extensions.conversationsDB
import com.simplemobiletools.smsmessenger.extensions.getConversations
import com.simplemobiletools.smsmessenger.extensions.getLatestMMS
import com.simplemobiletools.smsmessenger.extensions.insertOrUpdateConversation
import com.simplemobiletools.smsmessenger.extensions.showReceivedMessageNotification
import com.simplemobiletools.smsmessenger.extensions.updateUnreadCountBadge
import com.simplemobiletools.smsmessenger.helpers.refreshMessages
import java.io.File
import java.text.SimpleDateFormat

// more info at https://github.com/klinker41/android-smsmms
class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        val normalizedAddress = address.normalizePhoneNumber()
        return context.isNumberBlocked(normalizedAddress)
    }

    // TODO move to share between MmsReceiver and SmsReceiver
    fun normalizeNumber(number: String): String {
        // https://en.wikipedia.org/wiki/E.164
        // maximum 15 digits
        // country code 1 to 3 digits
        var newnumber:String = number
        if (number.length == 10) newnumber = "+1" + number // assume "local" US number with area code
        if (number.length == 11) newnumber = "+" + number // assume local US number with country code but no +
        return newnumber
    }

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        val mms = context.getLatestMMS() ?: return
        val numbers = mms.participants.map{
            it.phoneNumbers?.first()?.normalizedNumber ?: "unknown"
        }
        val from = numbers[0]
        // make sure numbers begin with +1
        val to = numbers.sorted().map{ normalizeNumber(it) }.joinToString(",")

        val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
        ensureBackgroundThread {
            val glideBitmap = try {
                Glide.with(context)
                    .asBitmap()
                    .load(mms.attachment!!.attachments.first().getUri())
                    .centerCrop()
                    .into(size, size)
                    .get()
            } catch (e: Exception) {
                null
            }

            Handler(Looper.getMainLooper()).post {
                //context.showReceivedMessageNotification(from, mms.body, mms.threadId, glideBitmap)
                val conversation = context.getConversations(mms.threadId).firstOrNull() ?: return@post
                ensureBackgroundThread {
                    context.insertOrUpdateConversation(conversation)

                    val path = context.getExternalFilesDir(null)
                    val messagesFile = File(path, "Messages")

                    var attachments = ""
                    if (mms.attachment?.attachments?.size!! > 0) {
                        attachments += " attachments(${mms.attachment?.attachments?.size}): "
                    }
                    mms.attachment?.attachments!!.forEach {
                        attachments += "$it "
                    }

                    messagesFile.appendText("${mms.date} ${from} ${to} ${mms.body} ${attachments}\n")
                    // TODO save attachments in some reasonable way

                    context.updateUnreadCountBadge(context.conversationsDB.getUnreadConversations())
                    refreshMessages()
                }
            }
        }
    }

    override fun onError(context: Context, error: String) = context.showErrorToast(context.getString(R.string.couldnt_download_mms))
}
