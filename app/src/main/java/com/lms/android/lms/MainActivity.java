package com.lms.android.lms;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.rekognition.AmazonRekognitionClient;
import com.amazonaws.services.rekognition.model.DetectTextRequest;
import com.amazonaws.services.rekognition.model.DetectTextResult;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.TextDetection;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int SELECT_PICTURE = 1;

    private Button uploadButton;
    private ListView detectedTextListView;
    private ArrayAdapter<String> arrayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uploadButton = (Button) findViewById(R.id.upload_button);
        detectedTextListView = (ListView) findViewById(R.id.detectedTextListView);

        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setType("image/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(intent, "Select Picture"), SELECT_PICTURE);

            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= 23) {
            int check1 = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_EXTERNAL_STORAGE);
            int check2 = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (check1 != PackageManager.PERMISSION_GRANTED && check2 != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, 101);
            }
        }
    }

    public String getPath(Uri uri) {
        String[] projection = {MediaStore.Images.Media.DATA};
        Cursor cursor = managedQuery(uri, projection, null, null, null);
        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        return cursor.getString(column_index);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == SELECT_PICTURE) {
                Uri selectedImageUri = data.getData();
                String selectedImagePath = getPath(selectedImageUri);

                if (selectedImagePath != null) {
                    detectText(selectedImagePath);
                }
            }
        }
    }

    public void detectText(String selectedImagePath) {
        File file = new File(selectedImagePath);

        try {
            InputStream in = new FileInputStream(file.getAbsolutePath());
            ByteBuffer imageBytes = ByteBuffer.wrap(IOUtils.toByteArray(in));

            Image image = new Image();
            image.withBytes(imageBytes);

            DetectTextTask task = new DetectTextTask();
            task.execute(image);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class DetectTextTask extends AsyncTask<Image, Void, List<TextDetection>> {
        @Override
        protected List<TextDetection> doInBackground(Image... params) {

            CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                    getApplicationContext(),
                    "Identity-Pool-ID",
                    Regions.US_WEST_2
            );

            AmazonRekognitionClient rekognitionClient = new AmazonRekognitionClient(credentialsProvider);

            DetectTextRequest request = new DetectTextRequest().withImage(params[0]);

            DetectTextResult result = rekognitionClient.detectText(request);
            return result.getTextDetections();
        }

        @Override
        protected void onPostExecute(List<TextDetection> textDetections) {
            super.onPostExecute(textDetections);

            List<String> detectedTextList = new ArrayList<>();

            for (TextDetection text : textDetections) {
                String resultstr = "";
                resultstr += "Text : " + text.getDetectedText() + "\n";
                resultstr += "Height : " + text.getGeometry().getBoundingBox().getHeight() + "\n";
                resultstr += "Width : " + text.getGeometry().getBoundingBox().getWidth() + "\n";
                resultstr += "Top : " + text.getGeometry().getBoundingBox().getTop() + "\n";
                resultstr += "Left : " + text.getGeometry().getBoundingBox().getLeft() + "\n";

                detectedTextList.add(resultstr);
            }

            arrayAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_list_item_1, detectedTextList);
            detectedTextListView.setAdapter(arrayAdapter);
        }
    }


    private class S3IntentTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {

            CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                    getApplicationContext(),
                    "Identity-Pool-ID",
                    Regions.US_WEST_2
            );

            AmazonS3Client s3Client = new AmazonS3Client(credentialsProvider);
            File fileToUpload = new File(params[0]);
            PutObjectRequest putRequest = new PutObjectRequest("text-detection", "photo",
                    fileToUpload);
            PutObjectResult putResponse = s3Client.putObject(putRequest);

            GetObjectRequest getRequest = new GetObjectRequest("text-detection", "photo");
            com.amazonaws.services.s3.model.S3Object getResponse = s3Client.getObject(getRequest);
            InputStream myObjectBytes = getResponse.getObjectContent();


            try {
                myObjectBytes.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }
    }
}
