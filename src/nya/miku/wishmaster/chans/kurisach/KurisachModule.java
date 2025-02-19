/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nya.miku.wishmaster.chans.kurisach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringEscapeUtils;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;

import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.message.BasicNameValuePair;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.chans.nullchan.AbstractInstant0chan;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONException;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public class KurisachModule extends AbstractInstant0chan {
    private static final String CHAN_NAME = "kurisa.ch";
    private static final String DOMAIN = "kurisa.ch";
    private static final SimpleBoardModel[] BOARDS = new SimpleBoardModel[] {
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "sg", "steins;gate", null, false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "vg", "video;games", null, false)
    };
    private static final int THREADS_PER_PAGE = 15;
    private static final long TIMEZONE_CORRECTION = 10800000;    //UTC+3 offset. Remove when timestamp will be fixed on server

    private Map<String, Boolean> captchaEnabledMap = null;

    public KurisachModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "Kurisu (kurisa.ch)";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_clairews, null);
    }
    
    @Override
    protected String getUsingDomain() {
        return DOMAIN;
    }
    
    @Override
    protected boolean canHttps() {
        return true;
    }
    
    @Override
    protected boolean canCloudflare() {
        return true;
    }
    
    private boolean loadOnlyNewPosts() {
        return loadOnlyNewPosts(true);
    }
    
    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        addOnlyNewPostsPreference(preferenceGroup, true);
        super.addPreferencesOnScreen(preferenceGroup);
    }
    
    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        List<SimpleBoardModel> boardsList = new ArrayList<>();
        String url = getUsingUrl() + "api.php?id=1&method=get_boards";
        JSONObject json;
        try {
            json = downloadJSONObject(url, false, listener, task);
            JSONObject result = json.getJSONObject("result");
            List<String> boardNames = new ArrayList<>(result.keySet());
            Collections.sort(boardNames);
            for (String boardName : boardNames) {
                JSONObject boardJson = result.getJSONObject(boardName);
                SimpleBoardModel model = new SimpleBoardModel();
                model.chan = getChanName();
                model.boardName = boardName;
                model.boardDescription = StringEscapeUtils.unescapeHtml4(boardJson.optString("name", boardName));
                boardsList.add(model);
                if (captchaEnabledMap == null) captchaEnabledMap = new HashMap<>();
                captchaEnabledMap.put(boardName, boardJson.optInt("captchaenabled", 1) == 1);
            }
            return boardsList.toArray(new SimpleBoardModel[0]);
        } catch (Exception e) {
            return BOARDS;
        }
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.attachmentsMaxCount = 1;
        model.allowReport = BoardModel.REPORT_WITH_COMMENT;
        return model;
    }
    
    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        String url = getUsingUrl() + "api.php?id=1&method=get_part_of_board&board=" + boardName
                + "&start=" + (page * THREADS_PER_PAGE) + "&threadnum=" + THREADS_PER_PAGE + "&previewnum=3";
        JSONObject json = downloadJSONObject(url, false, listener, task);
        try {
            JSONArray threads = json.getJSONArray("result");
            ThreadModel[] list = new ThreadModel[threads.length()];
            for (int i=0; i<threads.length(); ++i) {
                list[i] = mapThreadModel(threads.getJSONObject(i), boardName);
            }
            return list;
        } catch (JSONException e) {
            throw new Exception(getErrorMessage(json));
        }
    }
    
    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        if (loadOnlyNewPosts() && (oldList != null) && (oldList.length > 0)) {
            String url = getUsingUrl() + "api.php?id=1&method=get_updates_to_thread&board=" + boardName
                    + "&thread_id=" + threadNumber + "&timestamp="
                        + (oldList[oldList.length - 1].timestamp + TIMEZONE_CORRECTION) / 1000;
            JSONObject jsonResponse = null;
            try {
                jsonResponse = downloadJSONObject(url, false, listener, task);
                List<PostModel> posts = new ArrayList<PostModel>(Arrays.asList(oldList));
                JSONObject result = jsonResponse.optJSONObject("result");
                if (result != null) {
                    posts.addAll(mapPostsSet(result, boardName));
                    return posts.toArray(new PostModel[posts.size()]);
                } else {
                    return oldList;
                }
            } catch (HttpWrongStatusCodeException e) {
                if (e.getStatusCode() == 404) {
                    return oldList;
                }
                throw e;
            } catch (Exception e) {
                throw new Exception(getErrorMessage(jsonResponse));
            }
        }
        
        String url = getUsingUrl() + "api.php?id=1&method=get_thread&board=" + boardName + "&thread_id=" + threadNumber;
        JSONObject jsonResponse = downloadJSONObject(url, false, listener, task);
        try {
            JSONObject result = jsonResponse.getJSONObject("result");
            List<PostModel> newList = mapPostsSet(result, boardName);
            return oldList == null ? newList.toArray(new PostModel[newList.size()])
                    : ChanModels.mergePostsLists(Arrays.asList(oldList), newList);
        } catch(JSONException e) {
            throw new Exception(getErrorMessage(jsonResponse));
        }
    }
    
    @Override
    public ThreadModel[] getCatalog(String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
       String url = getUsingUrl() + "api.php?id=1&method=get_part_of_board&board=" + boardName
               + "&previewnum=0&start=0&threadnum=" + Integer.MAX_VALUE;
       JSONObject response = downloadJSONObject(url, false, listener, task);
       try {
           JSONArray threads = response.getJSONArray("result");
           ThreadModel[] newList = new ThreadModel[threads.length()];
           for (int i=0; i<threads.length(); ++i) {
               newList[i] = mapThreadModel(threads.getJSONObject(i), boardName);
           }
           return newList;
       } catch(JSONException e) {
           throw new Exception(getErrorMessage(response));
       }
    }
    
    private ThreadModel mapThreadModel(JSONObject json, String boardName) {
        ThreadModel model = new ThreadModel();
        model.postsCount = json.optInt("numreplies", -2) + 1;
        model.attachmentsCount = json.optInt("numpicreplies", -2) + 1;
        JSONObject opFlags = json.optJSONObject("opflags");
        if (opFlags != null) {
            model.isSticky = opFlags.optInt("stickied") == 1;
            model.isClosed = opFlags.optInt("locked") == 1;
        }
        List<PostModel> postsList = new ArrayList<PostModel>();
        postsList.add(mapPostModel(json.getJSONObject("op"), boardName));
        JSONObject replies = json.optJSONObject("lastreplies");
        if (replies != null) {
            postsList.addAll(mapPostsSet(replies, boardName));
        }
        model.posts = postsList.toArray(new PostModel[postsList.size()]);
        model.threadNumber = model.posts[0].number;
        return model;
    }
    
    private PostModel mapPostModel(JSONObject json, String boardName) {
        PostModel model = new PostModel();
        model.number = json.optString("id", null);
        if (model.number == null) throw new RuntimeException();
        model.name = StringEscapeUtils.unescapeHtml4(RegexUtils.removeHtmlTags(json.optString("name")));
        model.subject = StringEscapeUtils.unescapeHtml4(json.optString("subject"));
        model.comment = json.optString("text").
                replace("\\\"", "\"").replace("\\'", "'");
        model.email = json.optString("email");
        model.trip = json.optString("tripcode");
        if (!model.trip.equals("") && !model.trip.startsWith("!")) model.trip = "!" + model.trip;
        model.icons = null;
        model.op = false;
        model.sage = model.email.toLowerCase(Locale.US).equals("sage");
        model.timestamp = json.optLong("datetime") * 1000 - TIMEZONE_CORRECTION;
        model.parentThread = json.optString("thread", model.number);
        String ext = json.optString("filetype");
        String fileName = json.optString("filename");
        if (ext.length() > 0 && fileName.length() > 0) {
            AttachmentModel attachment = new AttachmentModel();
            switch (ext.toLowerCase(Locale.US)) {
                case "jpeg":
                case "jpg":
                case "png":
                    attachment.type = AttachmentModel.TYPE_IMAGE_STATIC;
                    break;
                case "gif":
                    attachment.type = AttachmentModel.TYPE_IMAGE_GIF;
                    break;
                case "mp3":
                case "ogg":
                    attachment.type = AttachmentModel.TYPE_AUDIO;
                    break;
                case "webm":
                case "mp4":
                    attachment.type = AttachmentModel.TYPE_VIDEO;
                    break;
                case "you":
                    attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                    attachment.thumbnail = "https://img.youtube.com/vi/" + fileName + "/default.jpg";
                    attachment.path = "https://youtube.com/watch?v=" + fileName;
                    break;
                case "cob":
                    attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                    attachment.path = "https://coub.com/view/" + fileName;
                    break;
                case "vim":
                    attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                    attachment.path = "https://vimeo.com/" + fileName;
                    break;
                default:
                    attachment.type = AttachmentModel.TYPE_OTHER_FILE;
            }
            attachment.size = attachment.type == AttachmentModel.TYPE_OTHER_NOTFILE ? -1
                    : json.optInt("filesize", -1);
            if (attachment.size > 0) attachment.size = Math.round(attachment.size / 1024f);
            attachment.width = json.optInt("pic_w", -1);
            attachment.height = json.optInt("pic_h", -1);
            attachment.isSpoiler = json.optInt("spoiler") == 1;
            if (attachment.type != AttachmentModel.TYPE_OTHER_NOTFILE) {
                if (!attachment.isSpoiler
                        && attachment.type != AttachmentModel.TYPE_AUDIO
                        && attachment.type != AttachmentModel.TYPE_VIDEO) {
                    attachment.thumbnail = "/" + boardName + "/thumb/" + fileName  + "s." + ext;
                }
                attachment.path = "/" + boardName + "/src/" + fileName + "." + ext;
            }
            model.attachments = new AttachmentModel[] { attachment };
        }
        return model;
    }
    
    private List<PostModel> mapPostsSet(JSONObject json, String boardName) {
        List<PostModel> posts = new ArrayList<PostModel>();
        List<String> keys = new ArrayList<String>(json.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String key1, String key2) {
                return Integer.valueOf(key1).compareTo(Integer.valueOf(key2));
            }
        });
        for (int i=0; i<keys.size(); ++i) {
            JSONObject currentJson = json.optJSONObject(keys.get(i));
            if (currentJson != null) {
                posts.add(mapPostModel(currentJson, boardName));
            }
        }
        return posts;
    }
    
    private String getErrorMessage(JSONObject response) {
        JSONObject result = response.optJSONObject("result");
        if (result != null) {
            return result.optString("message");
        } else {
            return response.optString("error");
        }
    }

    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        if (captchaEnabledMap != null && captchaEnabledMap.containsKey(boardName) && !captchaEnabledMap.get(boardName)) {
            return null;
        } else {
            return super.getNewCaptcha(boardName, threadNumber, listener, task);
        }
    }
    
    @Override
    protected void setSendPostEntityAttachments(SendPostModel model, ExtendedMultipartBuilder postEntityBuilder) throws Exception {
        if (model.attachments != null && model.attachments.length > 0) {
            postEntityBuilder.addFile("imagefile", model.attachments[0], model.randomHash);
            if (model.custommark) postEntityBuilder.addString("picspoiler", "1");
        }
    }
    
    @Override
    protected List<? extends NameValuePair> getReportFormAllValues(DeletePostModel model) {
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new BasicNameValuePair("board", model.boardName));
        pairs.add(new BasicNameValuePair("post[]", model.postNumber));
        pairs.add(new BasicNameValuePair("reportreason", model.reportReason));
        pairs.add(new BasicNameValuePair("reportpost", "Репорт"));
        return pairs;
    }
    
}
