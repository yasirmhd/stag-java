package com.vimeo.stag.processor;

import com.vimeo.stag.UseStag;

@UseStag
public class FinalFields {

    final String finalString;

    public FinalFields()
    {
        finalString = null;
    }

    public FinalFields(String finalString)
    {
        this.finalString = finalString;
    }

}